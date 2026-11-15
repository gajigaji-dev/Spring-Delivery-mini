package com.sparta.springdeliverymini.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

// V12(주문 목록 페이지 조회용 인덱스)가 V11 데이터가 있는 DB에서도 적용되는지,
// 기존 주문을 건드리지 않는지, 고객별·매장별 주문 조회가 실제로 새 인덱스를 쓸 수 있는지 검증
class OrderListIndexMigrationTest {

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V11까지만 적용
        flyway(dataSource, "11").migrate();

        // 2. 기존 데이터 — 고객 2명, 매장 1개, 주문 3건
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1'), "
                + "(2, 'pw', 'CUSTOMER', 'customer1'), (3, 'pw', 'CUSTOMER', 'customer2')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1)");
        jdbc.update("INSERT INTO orders (id, delivery_address, status, total_price, user_id, store_id) VALUES "
                + "(1, '서울시', 'ORDER_REQUEST', 10000, 2, 1), "
                + "(2, '서울시', 'ORDER_REQUEST', 20000, 3, 1), "
                + "(3, '서울시', 'ORDER_CANCELLED', 30000, 2, 1)");

        // 3. 최신 버전까지 적용
        flyway(dataSource, "latest").migrate();
    }

    @AfterAll
    static void tearDown() {
        postgres.stop();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private String indexDefinition(String indexName) {
        return jdbc.query("SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = ?",
                rs -> rs.next() ? rs.getString(1) : null, indexName);
    }

    @Test
    void 고객별_매장별_주문_조회용_인덱스가_조건_컬럼과_id_순서로_만들어진다() {
        assertThat(indexDefinition("idx_orders_user_id_id")).contains("(user_id, id)");
        assertThat(indexDefinition("idx_orders_store_id_id")).contains("(store_id, id)");
    }

    @Test
    void 역할이_겹치는_기존_store_id_인덱스는_제거되고_나머지_매장_인덱스는_유지된다() {
        assertThat(indexDefinition("idx_orders_store_id")).isNull();
        assertThat(indexDefinition("idx_orders_store_delivered_at")).isNotNull();
        assertThat(indexDefinition("idx_orders_store_status")).isNotNull();
    }

    @Test
    void 기존_주문은_그대로_유지된다() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT sum(total_price) FROM orders WHERE user_id = 2", Long.class))
                .isEqualTo(40000L);
    }

    @Test
    void 주문_페이지_조회는_새_인덱스로_정렬_없이_처리된다() {
        assertThat(plan("SELECT * FROM orders WHERE user_id = 2 ORDER BY id LIMIT 21"))
                .contains("idx_orders_user_id_id").doesNotContain("Sort");
        assertThat(plan("SELECT * FROM orders WHERE store_id = 1 ORDER BY id LIMIT 21"))
                .contains("idx_orders_store_id_id").doesNotContain("Sort");
    }

    // 행이 적으면 플래너가 순차 스캔을 고르므로 순차 스캔을 끄고, 이 쿼리에 인덱스를 쓸 수 있는지만 확인
    // (실제 데이터 규모에서의 계획·수치는 docs/performance.md)
    private String plan(String sql) {
        return jdbc.execute((java.sql.Connection connection) -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                statement.execute("SET enable_bitmapscan = off");
                StringBuilder lines = new StringBuilder();
                try (java.sql.ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) {
                        lines.append(rs.getString(1)).append('\n');
                    }
                }
                return lines.toString();
            }
        });
    }
}
