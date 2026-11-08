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
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V10(매장별 수수료율)이 V9 데이터가 있는 DB에서도 안전하게 적용되는지,
// 기존 결제 스냅샷·정산을 건드리지 않는지, 새 CHECK 제약이 잘못된 값을 거부하는지 검증
class StoreFeeRateMigrationTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 5, 11, 0);
    private static final LocalDateTime DELIVERED_AT = LocalDateTime.of(2026, 11, 5, 12, 0);

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V9까지만 적용
        flyway(dataSource, "9").migrate();

        // 2. 기존 데이터 — 매장 2개, 5%로 결제된 결제 1건, PAID 정산 1건
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1'), "
                + "(2, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1), (2, '피자집', 1)");
        jdbc.update("INSERT INTO orders (id, delivery_address, status, total_price, user_id, store_id, delivered_at) "
                + "VALUES (1, '서울시', 'DELIVERY_COMPLETED', 10000, 2, 1, ?)", DELIVERED_AT);
        jdbc.update("INSERT INTO payments (id, amount, paid_at, payment_method, order_id, fee_rate, fee_amount, settlement_amount) "
                + "VALUES (1, 10000, ?, 'CARD', 1, 0.05, 500, 9500)", PAID_AT);
        jdbc.update("INSERT INTO settlements (id, store_id, settlement_date, payment_count, total_amount, total_fee, "
                        + "sales_settlement_amount, settlement_amount, status, confirmed_at, paid_at) VALUES "
                        + "(1, 1, '2026-11-05', 1, 10000, 500, 9500, 9500, 'PAID', ?, ?)",
                DELIVERED_AT.plusDays(1), DELIVERED_AT.plusDays(2));

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

    @Test
    void fee_rate_컬럼은_numeric_5_4이고_NULL을_허용한다() {
        Map<String, Object> column = jdbc.queryForMap(
                "SELECT data_type, numeric_precision, numeric_scale, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'stores' AND column_name = 'fee_rate'");

        assertThat(column.get("data_type")).isEqualTo("numeric");
        assertThat(column.get("numeric_precision")).isEqualTo(5);
        assertThat(column.get("numeric_scale")).isEqualTo(4);
        assertThat(column.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void 기존_매장은_수수료율이_NULL이라_공통_수수료율을_그대로_사용한다() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stores WHERE fee_rate IS NOT NULL", Integer.class))
                .isZero();
    }

    @Test
    void 기존_결제_스냅샷과_정산은_바뀌지_않는다() {
        Map<String, Object> payment = jdbc.queryForMap(
                "SELECT fee_rate, fee_amount, settlement_amount FROM payments WHERE id = 1");
        assertThat((BigDecimal) payment.get("fee_rate")).isEqualByComparingTo("0.05");
        assertThat(payment.get("fee_amount")).isEqualTo(500L);
        assertThat(payment.get("settlement_amount")).isEqualTo(9500L);

        Map<String, Object> settlement = jdbc.queryForMap(
                "SELECT total_fee, settlement_amount, status FROM settlements WHERE id = 1");
        assertThat(settlement.get("total_fee")).isEqualTo(500L);
        assertThat(settlement.get("settlement_amount")).isEqualTo(9500L);
        assertThat(settlement.get("status")).isEqualTo("PAID");
    }

    @Test
    void 범위_안의_수수료율과_NULL은_저장된다() {
        jdbc.update("UPDATE stores SET fee_rate = 0 WHERE id = 2");
        jdbc.update("UPDATE stores SET fee_rate = 1 WHERE id = 2");
        jdbc.update("UPDATE stores SET fee_rate = 0.0300 WHERE id = 2");
        assertThat(jdbc.queryForObject("SELECT fee_rate FROM stores WHERE id = 2", BigDecimal.class))
                .isEqualByComparingTo("0.03");

        jdbc.update("UPDATE stores SET fee_rate = NULL WHERE id = 2");
        assertThat(jdbc.queryForObject("SELECT fee_rate FROM stores WHERE id = 2", BigDecimal.class)).isNull();
    }

    @Test
    void 범위를_벗어난_수수료율은_DB가_거부한다() {
        for (String invalid : new String[]{"-0.0001", "1.0001", "1.5"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE stores SET fee_rate = " + invalid + " WHERE id = 2"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("stores_fee_rate_check");
        }
    }
}
