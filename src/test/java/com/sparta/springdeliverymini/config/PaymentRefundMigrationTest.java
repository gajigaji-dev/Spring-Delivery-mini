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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V9(결제 상태, 정산 환불·이월, 차감 연결 테이블)이 V8 데이터가 있는 DB에서도 안전하게 적용되는지,
// 새 제약이 잘못된 행을 거부하는지 검증
class PaymentRefundMigrationTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 5, 11, 0);
    private static final LocalDateTime DELIVERED_AT = LocalDateTime.of(2026, 11, 5, 12, 0);
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 6, 9, 0);
    private static final LocalDateTime PAID_OUT_AT = LocalDateTime.of(2026, 11, 7, 10, 0);

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;
    // 테스트에서 id를 직접 지정하므로 겹치지 않게 증가
    private static final AtomicLong ids = new AtomicLong(100);

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V8까지만 적용
        flyway(dataSource, "8").migrate();

        // 2. 기존 데이터 — 배달완료 주문의 결제 1건, PENDING·PAID 정산
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1'), "
                + "(2, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1)");
        jdbc.update("INSERT INTO orders (id, delivery_address, status, total_price, user_id, store_id, delivered_at) "
                + "VALUES (1, '서울시', 'DELIVERY_COMPLETED', 10000, 2, 1, ?)", DELIVERED_AT);
        jdbc.update("INSERT INTO payments (id, amount, paid_at, payment_method, order_id, fee_rate, fee_amount, settlement_amount) "
                + "VALUES (1, 10000, ?, 'CARD', 1, 0.05, 500, 9500)", PAID_AT);
        jdbc.update("INSERT INTO settlements (id, store_id, settlement_date, payment_count, total_amount, total_fee, "
                        + "settlement_amount, status, confirmed_at, paid_at) VALUES "
                        + "(1, 1, '2026-11-05', 1, 10000, 500, 9500, 'PENDING', NULL, NULL), "
                        + "(2, 1, '2026-11-04', 1, 10000, 500, 9500, 'PAID', ?, ?)",
                CONFIRMED_AT, PAID_OUT_AT);

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

    private static long id() {
        return ids.incrementAndGet();
    }

    // 금액·상태가 유효한 정산 1건 (판매만) — 컬럼을 바꿔 가며 제약을 시험할 때 사용
    private static long insertSettlement(String date, long sales, long refund, long carriedIn, long payout, long carriedOut) {
        long id = id();
        jdbc.update("INSERT INTO settlements (id, store_id, settlement_date, payment_count, total_amount, total_fee, "
                        + "sales_settlement_amount, refund_count, refund_amount, refund_fee, refund_settlement_amount, "
                        + "carried_in_amount, carried_out_amount, settlement_amount, status) "
                        + "VALUES (?, 1, CAST(? AS date), 1, ?, 0, ?, 0, ?, 0, ?, ?, ?, ?, 'PENDING')",
                id, date, sales, sales, refund, refund, carriedIn, carriedOut, payout);
        return id;
    }

    // ===== 기존 데이터 =====

    @Test
    void 기존_결제는_COMPLETED가_되고_취소_환불_시각은_비어_있다() {
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = 1", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM payments WHERE id = 1", LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT refunded_at FROM payments WHERE id = 1", LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT settlement_amount FROM payments WHERE id = 1", Long.class)).isEqualTo(9500);
    }

    @Test
    void 기존_정산은_판매_정산액이_채워지고_환불_이월은_0이며_지급액과_상태는_그대로다() {
        for (long id : new long[]{1, 2}) {
            assertThat(jdbc.queryForObject("SELECT sales_settlement_amount FROM settlements WHERE id = ?", Long.class, id))
                    .isEqualTo(9500);
            assertThat(jdbc.queryForObject("SELECT settlement_amount FROM settlements WHERE id = ?", Long.class, id))
                    .isEqualTo(9500);
            assertThat(jdbc.queryForObject("SELECT refund_count + refund_amount + refund_fee + refund_settlement_amount "
                    + "+ carried_in_amount + carried_out_amount FROM settlements WHERE id = ?", Long.class, id)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id = 2", String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT paid_at FROM settlements WHERE id = 2", LocalDateTime.class)).isEqualTo(PAID_OUT_AT);
    }

    // ===== payments 제약 =====

    @Test
    void 결제_상태와_취소_환불_시각이_어긋나면_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("payments_refunded_at_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("payments_cancelled_at_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET refunded_at = ? WHERE id = 1", CONFIRMED_AT))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("payments_refunded_at_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'UNKNOWN' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("payments_status_check");
    }

    // ===== settlements 제약 =====

    @Test
    void 지급액_이월_순액_규칙에_맞는_정산은_저장된다() {
        // 판매 9500 - 환불 28500 → 지급 0, 이월 19000
        insertSettlement("2026-10-01", 9500, 28500, 0, 0, 19000);
        // 판매 47500 - 이월받은 9500 → 지급 38000
        insertSettlement("2026-10-02", 47500, 0, 9500, 38000, 0);
    }

    @Test
    void 지급액과_이월액이_둘_다_양수이면_거부한다() {
        // 순액 식(10000 - 5000 = 5000)은 맞지만 지급액과 이월액이 둘 다 양수
        assertThatThrownBy(() -> insertSettlement("2026-10-03", 5000, 0, 0, 10000, 5000))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlements_payout_check");
    }

    @Test
    void 순액_식이_맞지_않으면_거부한다() {
        assertThatThrownBy(() -> insertSettlement("2026-10-04", 9500, 0, 0, 9000, 0))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlements_net_amount_check");
        assertThatThrownBy(() -> insertSettlement("2026-10-05", 9500, 9500, 0, 0, 100))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlements_net_amount_check");
    }

    @Test
    void 음수_금액은_거부한다() {
        assertThatThrownBy(() -> insertSettlement("2026-10-06", 9500, 0, -100, 9600, 0))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlements_payout_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET refund_fee = -1, refund_amount = -1 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlements_refund_amount_check");
    }

    // ===== 차감 연결 테이블 =====

    @Test
    void 같은_환불을_두_정산에서_차감할_수_없다() {
        long first = insertSettlement("2026-10-10", 9500, 0, 0, 9500, 0);
        long second = insertSettlement("2026-10-11", 9500, 0, 0, 9500, 0);
        jdbc.update("INSERT INTO settlement_refunds (id, settlement_id, payment_id, amount, fee_amount, settlement_amount) "
                + "VALUES (?, ?, 1, 10000, 500, 9500)", id(), first);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement_refunds (id, settlement_id, payment_id, amount, "
                + "fee_amount, settlement_amount) VALUES (?, ?, 1, 10000, 500, 9500)", id(), second))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_settlement_refunds_payment");

        jdbc.update("DELETE FROM settlement_refunds WHERE payment_id = 1");
    }

    @Test
    void 환불_기록의_금액이_맞지_않으면_거부한다() {
        long settlement = insertSettlement("2026-10-12", 9500, 0, 0, 9500, 0);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement_refunds (id, settlement_id, payment_id, amount, "
                + "fee_amount, settlement_amount) VALUES (?, ?, 1, 10000, 500, 9000)", id(), settlement))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlement_refunds_amount_check");
    }

    @Test
    void 같은_이월분을_두_번_차감하거나_자기_자신에게_이월할_수_없다() {
        long from = insertSettlement("2026-10-20", 0, 9500, 0, 0, 9500);
        long to1 = insertSettlement("2026-10-21", 9500, 0, 9500, 0, 0);
        long to2 = insertSettlement("2026-10-22", 9500, 0, 9500, 0, 0);
        jdbc.update("INSERT INTO settlement_carryovers (id, from_settlement_id, to_settlement_id, amount) VALUES (?, ?, ?, 9500)",
                id(), from, to1);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement_carryovers (id, from_settlement_id, to_settlement_id, "
                + "amount) VALUES (?, ?, ?, 9500)", id(), from, to2))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_settlement_carryovers_from");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement_carryovers (id, from_settlement_id, to_settlement_id, "
                + "amount) VALUES (?, ?, ?, 9500)", id(), to2, to2))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlement_carryovers_self_check");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement_carryovers (id, from_settlement_id, to_settlement_id, "
                + "amount) VALUES (?, ?, ?, 0)", id(), to1, to2))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlement_carryovers_amount_check");
    }
}
