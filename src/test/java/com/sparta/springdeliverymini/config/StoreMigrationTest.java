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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V4(매장 도입)가 이미 메뉴·주문 데이터가 있는 DB에서도 안전하게 적용되는지 검증
// V3까지 적용한 DB에 기존 데이터를 넣고 → V4를 적용한 뒤 매장이 백필되고 메뉴·주문이 올바른 매장에 연결되는지 확인
class StoreMigrationTest {

    private static PostgreSQLContainer postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V3까지만 적용 (매장 없음, 메뉴는 owner_id로 점주를 가짐)
        flyway("3").migrate();

        // 2. 기존 데이터
        //    owner1: 메뉴 2개 / owner2: 메뉴 없음 / owner3: 메뉴 1개 / customer1: 주문 2건
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES "
                + "(1, 'pw', 'OWNER', 'owner1'), (2, 'pw', 'OWNER', 'owner2'), "
                + "(3, 'pw', 'OWNER', 'owner3'), (4, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO menus (id, name, price, owner_id) VALUES "
                + "(1, '후라이드 치킨', 18000, 1), (2, '양념 치킨', 19000, 1), (3, '피자', 20000, 3)");
        jdbc.update("INSERT INTO orders (id, delivery_address, quantity, status, total_price, menu_id, user_id) VALUES "
                + "(1, '서울시', 1, 'ORDER_REQUEST', 18000, 2, 4), (2, '서울시', 1, 'ORDER_REQUEST', 20000, 3, 4)");

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

    private Long storeIdOf(Long ownerId) {
        return jdbc.queryForObject("SELECT id FROM stores WHERE owner_id = ?", Long.class, ownerId);
    }

    @Test
    void 메뉴를_가진_점주마다_기본_매장이_1개씩_생긴다() {
        List<String> names = jdbc.queryForList("SELECT name FROM stores ORDER BY owner_id", String.class);

        // 메뉴가 없는 owner2는 매장이 생기지 않음
        assertThat(names).containsExactly("owner1의 매장", "owner3의 매장");
    }

    @Test
    void 기존_메뉴는_점주의_매장으로_연결된다() {
        assertThat(jdbc.queryForList("SELECT store_id FROM menus WHERE id IN (1, 2)", Long.class))
                .containsOnly(storeIdOf(1L));
        assertThat(jdbc.queryForObject("SELECT store_id FROM menus WHERE id = 3", Long.class))
                .isEqualTo(storeIdOf(3L));
    }

    @Test
    void 기존_주문은_주문한_메뉴의_매장으로_연결된다() {
        assertThat(jdbc.queryForObject("SELECT store_id FROM orders WHERE id = 1", Long.class))
                .isEqualTo(storeIdOf(1L));
        assertThat(jdbc.queryForObject("SELECT store_id FROM orders WHERE id = 2", Long.class))
                .isEqualTo(storeIdOf(3L));
    }

    @Test
    void menus_owner_id_컬럼은_제거된다() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'menus' AND column_name = 'owner_id'",
                Integer.class);

        assertThat(count).isZero();
    }

    @Test
    void 매장_없는_메뉴와_주문은_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO menus (name, price, store_id) VALUES ('매장 없는 메뉴', 1000, NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE orders SET store_id = NULL WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 없는_매장을_참조하면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET store_id = 9999 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_orders_store");
    }
}
