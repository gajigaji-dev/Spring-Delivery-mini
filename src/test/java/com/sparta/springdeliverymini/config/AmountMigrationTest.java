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

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V3(금액 bigint 전환 + 결제 수수료 컬럼)가 이미 데이터가 있는 DB에서도 안전하게 적용되는지 검증
// V2까지 적용한 DB에 기존 데이터를 넣고 → V3를 적용한 뒤 값이 보존·백필되는지 확인
// (다른 통합 테스트는 빈 DB에 전체 마이그레이션만 실행하므로 기존 데이터 변환은 검증하지 못함)
class AmountMigrationTest {

    private static PostgreSQLContainer postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V2까지만 적용 (V3 이전 스키마: 금액 컬럼 integer, 수수료 컬럼 없음)
        flyway("2").migrate();

        // 2. 기존 데이터 — integer 최대값 그대로의 메뉴 가격과 결제 1건
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1'), (2, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO menus (id, name, price, owner_id) VALUES (1, '후라이드 치킨', 2147483647, 1)");
        jdbc.update("INSERT INTO orders (id, delivery_address, quantity, status, total_price, menu_id, user_id) "
                + "VALUES (1, '서울시', 1, 'PAYMENT_COMPLETED', 36000, 1, 2)");
        jdbc.update("INSERT INTO payments (id, amount, paid_at, payment_method, order_id) "
                + "VALUES (1, 36000, '2026-11-05 12:00:00', 'CARD', 1)");

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

    private String columnType(String table, String column) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    @Test
    void 금액_컬럼은_bigint로_바뀐다() {
        assertThat(columnType("menus", "price")).isEqualTo("bigint");
        assertThat(columnType("orders", "total_price")).isEqualTo("bigint");
        assertThat(columnType("payments", "amount")).isEqualTo("bigint");
        assertThat(columnType("payments", "fee_amount")).isEqualTo("bigint");
        assertThat(columnType("payments", "settlement_amount")).isEqualTo("bigint");
        assertThat(columnType("payments", "fee_rate")).isEqualTo("numeric");
    }

    @Test
    void 기존_금액은_그대로_보존된다() {
        assertThat(jdbc.queryForObject("SELECT price FROM menus WHERE id = 1", Long.class))
                .isEqualTo(2_147_483_647L);
        assertThat(jdbc.queryForObject("SELECT total_price FROM orders WHERE id = 1", Long.class))
                .isEqualTo(36000L);
    }

    @Test
    void 수수료_도입_전_결제는_수수료_0_정산액_결제액으로_채워진다() {
        Map<String, Object> payment = jdbc.queryForMap(
                "SELECT amount, fee_rate, fee_amount, settlement_amount FROM payments WHERE id = 1");

        assertThat((BigDecimal) payment.get("fee_rate")).isEqualByComparingTo("0");
        assertThat(payment.get("fee_amount")).isEqualTo(0L);
        assertThat(payment.get("settlement_amount")).isEqualTo(36000L);
    }

    @Test
    void int_범위를_넘는_금액을_저장할_수_있다() {
        jdbc.update("UPDATE orders SET total_price = 3000000000 WHERE id = 1");

        assertThat(jdbc.queryForObject("SELECT total_price FROM orders WHERE id = 1", Long.class))
                .isEqualTo(3_000_000_000L);

        jdbc.update("UPDATE orders SET total_price = 36000 WHERE id = 1"); // 다른 테스트를 위해 원복
    }

    @Test
    void 수수료와_정산액의_합이_결제액과_다르면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET fee_amount = 100 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 합계가_bigint를_넘는_값도_CHECK_위반으로_거부한다() {
        // fee_amount + settlement_amount를 그대로 더하면 bigint 범위를 넘는 조합
        // → 덧셈 오버플로(22003)가 아니라 CHECK 위반(23514)으로 거부되어야 함
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET fee_amount = 9223372036854775807, settlement_amount = 1 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_fee_amount_check");
    }

    @Test
    void 결제액이_bigint_최소값이어도_CHECK_위반으로_거부한다() {
        // amount - fee_amount 형태였다면 여기서 뺄셈 오버플로가 남
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET amount = -9223372036854775808, fee_amount = 1, settlement_amount = 0 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_fee_amount_check");
    }

    @Test
    void 수수료가_음수면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET fee_amount = -1, settlement_amount = 36001 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_fee_amount_check");
    }

    @Test
    void 수수료율이_1을_넘으면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET fee_rate = 1.5 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_fee_rate_check");
    }

    @Test
    void 수수료율이_음수면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET fee_rate = -0.0001 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_fee_rate_check");
    }
}
