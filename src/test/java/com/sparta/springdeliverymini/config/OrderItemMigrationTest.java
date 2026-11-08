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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V5(주문 항목 도입)가 이미 주문 데이터가 있는 DB에서도 안전하게 적용되는지 검증
// V4까지 적용한 DB에 기존 주문을 넣고 → V5를 적용한 뒤 주문마다 항목 1개가 백필되는지 확인
class OrderItemMigrationTest {

    private static PostgreSQLContainer postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V4까지만 적용 (주문이 menu_id·quantity를 직접 가짐)
        flyway("4").migrate();

        // 2. 기존 데이터
        //    주문 1: 후라이드 치킨 2개 (주문 당시 18000원)
        //    주문 2: 양념 치킨 1개 (주문 당시 19000원) — 이후 메뉴 가격이 21000원으로 오른 상황
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES "
                + "(1, 'pw', 'OWNER', 'owner1'), (2, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1)");
        jdbc.update("INSERT INTO menus (id, name, price, store_id) VALUES "
                + "(1, '후라이드 치킨', 18000, 1), (2, '양념 치킨', 21000, 1)");
        jdbc.update("INSERT INTO orders (id, delivery_address, quantity, status, total_price, menu_id, user_id, store_id) VALUES "
                + "(1, '서울시', 2, 'ORDER_REQUEST', 36000, 1, 2, 1), "
                + "(2, '서울시', 1, 'PAYMENT_COMPLETED', 19000, 2, 2, 1)");

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

    private int columnCount(String table, String column) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }

    @Test
    void 기존_주문마다_항목이_1개씩_생긴다() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = 2", Integer.class)).isEqualTo(1);
    }

    @Test
    void 항목에는_메뉴_이름과_수량이_옮겨진다() {
        Map<String, Object> item = jdbc.queryForMap(
                "SELECT menu_id, menu_name, unit_price, quantity FROM order_items WHERE order_id = 1");

        assertThat(item.get("menu_id")).isEqualTo(1L);
        assertThat(item.get("menu_name")).isEqualTo("후라이드 치킨");
        assertThat(item.get("unit_price")).isEqualTo(18000L);
        assertThat(item.get("quantity")).isEqualTo(2);
    }

    @Test
    void 단가는_현재_메뉴_가격이_아닌_주문_당시_금액으로_복원된다() {
        // 메뉴는 21000원으로 올랐지만 주문 당시 총액 19000 / 수량 1 = 19000원
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id = 2", Long.class))
                .isEqualTo(19000L);
    }

    @Test
    void 기존_총액은_그대로_유지된다() {
        assertThat(jdbc.queryForObject("SELECT total_price FROM orders WHERE id = 1", Long.class)).isEqualTo(36000L);
        assertThat(jdbc.queryForObject(
                "SELECT sum(unit_price * quantity) FROM order_items WHERE order_id = 1", Long.class)).isEqualTo(36000L);
    }

    @Test
    void orders의_menu_id_quantity_컬럼은_제거된다() {
        assertThat(columnCount("orders", "menu_id")).isZero();
        assertThat(columnCount("orders", "quantity")).isZero();
    }

    @Test
    void 한_주문에_같은_메뉴를_두_항목으로_넣으면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO order_items (order_id, menu_id, menu_name, unit_price, quantity) "
                        + "VALUES (1, 1, '후라이드 치킨', 18000, 1)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_order_items_order_menu");
    }

    @Test
    void 수량이_1_미만이면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE order_items SET quantity = 0 WHERE order_id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("order_items_quantity_check");
    }

    @Test
    void 주문을_삭제하면_항목도_함께_삭제된다() {
        jdbc.update("INSERT INTO orders (id, delivery_address, status, total_price, user_id, store_id) "
                + "VALUES (99, '서울시', 'ORDER_REQUEST', 18000, 2, 1)");
        jdbc.update("INSERT INTO order_items (order_id, menu_id, menu_name, unit_price, quantity) "
                + "VALUES (99, 1, '후라이드 치킨', 18000, 1)");

        jdbc.update("DELETE FROM orders WHERE id = 99");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = 99", Integer.class)).isZero();
    }
}
