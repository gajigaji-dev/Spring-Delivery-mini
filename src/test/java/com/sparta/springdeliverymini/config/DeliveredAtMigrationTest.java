package com.sparta.springdeliverymini.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V6(배달완료 시각 추가)가 이미 주문 데이터가 있는 DB에서도 안전하게 적용되는지 검증
// V5까지 적용한 DB에 여러 상태의 주문을 넣고 → V6을 적용한 뒤 배달완료 주문만 백필되는지 확인
class DeliveredAtMigrationTest {

    private static final LocalDateTime DELIVERED = LocalDateTime.of(2026, 11, 5, 23, 59, 59);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 5, 22, 0);

    private static PostgreSQLContainer postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V5까지만 적용 (delivered_at 없음)
        flyway("5").migrate();

        // 2. 기존 데이터
        //    주문 1: 배달완료 — updated_at이 배달완료 시각
        //    주문 2: 배달완료 — updated_at이 비어 있는 오래된 행 → created_at으로 대신
        //    주문 3: 주문수락 — 배달 전이라 NULL 유지
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES "
                + "(1, 'pw', 'OWNER', 'owner1'), (2, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1)");
        jdbc.update("INSERT INTO orders (id, delivery_address, status, total_price, user_id, store_id, created_at, updated_at) VALUES "
                        + "(1, '서울시', 'DELIVERY_COMPLETED', 18000, 2, 1, ?, ?), "
                        + "(2, '서울시', 'DELIVERY_COMPLETED', 18000, 2, 1, ?, NULL), "
                        + "(3, '서울시', 'ORDER_ACCEPTED', 18000, 2, 1, ?, ?)",
                CREATED, DELIVERED, CREATED, CREATED, CREATED);

        // 3. 최신 버전까지 적용
        flyway("latest").migrate();
    }

    @AfterAll
    static void tearDown() {
        postgres.stop();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private LocalDateTime deliveredAt(long orderId) {
        return jdbc.queryForObject("SELECT delivered_at FROM orders WHERE id = ?", LocalDateTime.class, orderId);
    }

    @Test
    void 기존_배달완료_주문은_updated_at으로_백필된다() {
        assertThat(deliveredAt(1)).isEqualTo(DELIVERED);
    }

    @Test
    void updated_at이_없으면_created_at으로_백필된다() {
        assertThat(deliveredAt(2)).isEqualTo(CREATED);
    }

    @Test
    void 배달완료_전_주문은_NULL로_남는다() {
        assertThat(deliveredAt(3)).isNull();
    }

    @Test
    void 배달완료_주문에_배달완료_시각이_없으면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET delivered_at = NULL WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("orders_delivered_at_check");
    }

    @Test
    void 배달완료_전_주문에_배달완료_시각이_있으면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET delivered_at = ? WHERE id = 3", DELIVERED))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("orders_delivered_at_check");
    }
}
