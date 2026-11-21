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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V21(부분 환불)이 기존 전액 환불·정산 차감 기록이 있는 DB에서 안전하게 적용되는지 (데이터 이전 검증)
// - 기존 전액 환불 1건 → 환불 기록 1건(금액 그대로), 이미 차감된 환불은 그 환불 기록에 연결
// - 기존 결제·정산 행의 금액은 그대로, 새 제약이 잘못된 상태를 거부
class PartialRefundMigrationTest {

    private static final LocalDateTime DELIVERED_AT = LocalDateTime.of(2026, 11, 5, 12, 0);
    private static final LocalDateTime REFUNDED_1 = LocalDateTime.of(2026, 11, 5, 15, 0);
    private static final LocalDateTime REFUNDED_2 = LocalDateTime.of(2026, 11, 6, 15, 0);

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V20까지만 적용
        flyway(dataSource, "20").migrate();

        // 2. 기존 데이터 — 배달완료 결제 3건: 1번 환불·정산에서 차감됨 / 2번 환불·아직 미차감 / 3번 환불 없음
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1'), "
                + "(2, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1)");
        for (int id = 1; id <= 3; id++) {
            jdbc.update("INSERT INTO orders (id, delivery_address, status, total_price, user_id, store_id, delivered_at) "
                    + "VALUES (?, '서울시', 'DELIVERY_COMPLETED', 10000, 2, 1, ?)", id, DELIVERED_AT);
        }
        jdbc.update("INSERT INTO payments (id, amount, paid_at, payment_method, order_id, fee_rate, fee_amount, "
                + "settlement_amount, status, refunded_at) VALUES "
                + "(1, 10000, ?, 'CARD', 1, 0.05, 500, 9500, 'REFUNDED', ?), "
                + "(2, 10000, ?, 'CARD', 2, 0.05, 500, 9500, 'REFUNDED', ?), "
                + "(3, 10000, ?, 'CARD', 3, 0.05, 500, 9500, 'COMPLETED', NULL)",
                DELIVERED_AT, REFUNDED_1, DELIVERED_AT, REFUNDED_2, DELIVERED_AT);
        // 11/5 정산: 판매 0건, 1번 환불 차감 → 지급 0, 9,500원 이월
        jdbc.update("INSERT INTO settlements (id, store_id, settlement_date, payment_count, total_amount, total_fee, "
                + "sales_settlement_amount, settlement_amount, status, refund_count, refund_amount, refund_fee, "
                + "refund_settlement_amount, carried_out_amount) VALUES "
                + "(1, 1, '2026-11-05', 0, 0, 0, 0, 0, 'PENDING', 1, 10000, 500, 9500, 9500)");
        jdbc.update("INSERT INTO settlement_refunds (id, settlement_id, payment_id, amount, fee_amount, settlement_amount) "
                + "VALUES (1, 1, 1, 10000, 500, 9500)");

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
    void 기존_전액_환불은_금액_그대로_환불_기록_1건씩으로_옮겨진다() {
        List<Map<String, Object>> refunds = jdbc.queryForList(
                "SELECT payment_id, amount, fee_amount, settlement_amount, refunded_at FROM payment_refunds "
                        + "WHERE reason = '전액 환불 (V21 이전 기록)' ORDER BY payment_id");

        assertThat(refunds).hasSize(2);
        assertThat(refunds.get(0)).containsEntry("payment_id", 1L).containsEntry("amount", 10000L)
                .containsEntry("fee_amount", 500L).containsEntry("settlement_amount", 9500L);
        assertThat(refunds.get(1)).containsEntry("payment_id", 2L);
        assertThat(((java.sql.Timestamp) refunds.get(1).get("refunded_at")).toLocalDateTime()).isEqualTo(REFUNDED_2);
    }

    @Test
    void 누적_환불액은_전액_환불이면_결제액_아니면_0이다() {
        assertThat(jdbc.queryForList("SELECT refunded_amount FROM payments ORDER BY id", Long.class))
                .containsExactly(10000L, 10000L, 0L);
    }

    @Test
    void 이미_차감된_환불은_옮겨진_환불_기록에_연결되고_미차감_환불은_다음_집계_대상으로_남는다() {
        Long linked = jdbc.queryForObject("SELECT pr.payment_id FROM settlement_refunds sr "
                + "JOIN payment_refunds pr ON pr.id = sr.payment_refund_id WHERE sr.id = 1", Long.class);
        Long unsettled = jdbc.queryForObject("SELECT count(*) FROM payment_refunds pr "
                + "WHERE pr.reason = '전액 환불 (V21 이전 기록)' AND NOT EXISTS "
                + "(SELECT 1 FROM settlement_refunds sr WHERE sr.payment_refund_id = pr.id)", Long.class);

        assertThat(linked).isEqualTo(1L);
        assertThat(unsettled).isEqualTo(1L); // 2번 결제의 환불
        // 정산 금액은 바뀌지 않음
        assertThat(jdbc.queryForMap("SELECT refund_amount, carried_out_amount FROM settlements WHERE id = 1"))
                .containsEntry("refund_amount", 10000L).containsEntry("carried_out_amount", 9500L);
    }

    @Test
    void 결제_상태와_누적_환불액이_어긋나면_거부한다() {
        // 부분 환불인데 누적 환불액이 0
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'PARTIALLY_REFUNDED' WHERE id = 3"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 결제액보다 큰 누적 환불액
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'PARTIALLY_REFUNDED', refunded_amount = 20000 "
                + "WHERE id = 3")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_환불을_두_정산에서_차감할_수_없지만_같은_결제의_다른_환불은_따로_차감된다() {
        Long refundOfPayment1 = jdbc.queryForObject(
                "SELECT id FROM payment_refunds WHERE payment_id = 1 AND reason = '전액 환불 (V21 이전 기록)'", Long.class);
        jdbc.update("INSERT INTO settlements (id, store_id, settlement_date, payment_count, total_amount, total_fee, "
                + "sales_settlement_amount, settlement_amount, status) VALUES "
                + "(2, 1, '2026-11-07', 0, 0, 0, 0, 0, 'PENDING')");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement_refunds (settlement_id, payment_id, payment_refund_id, "
                + "amount, fee_amount, settlement_amount) VALUES (2, 1, ?, 10000, 500, 9500)", refundOfPayment1))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 결제 1의 두 번째 환불이라고 가정한 새 환불 기록 → payment_id가 같아도 차감 가능 (예전 payment_id UNIQUE는 없어짐)
        Long secondRefund = jdbc.queryForObject("INSERT INTO payment_refunds (payment_id, amount, fee_amount, "
                + "settlement_amount, reason, refunded_at) VALUES (1, 1000, 50, 950, '테스트', now()) RETURNING id", Long.class);
        jdbc.update("INSERT INTO settlement_refunds (settlement_id, payment_id, payment_refund_id, amount, fee_amount, "
                + "settlement_amount) VALUES (2, 1, ?, 1000, 50, 950)", secondRefund);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_refunds WHERE payment_id = 1", Long.class))
                .isEqualTo(2L);
    }
}
