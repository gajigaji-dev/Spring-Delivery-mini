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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V14(DB 결제 멱등성 기록, 알림 발송 상태, Outbox 인덱스)가 V13 데이터가 있는 DB에서도 안전하게 적용되는지,
// 기존 알림이 뒤늦게 발송되지 않는지(SKIPPED), 새 제약이 잘못된 값을 거부하는지 검증
class OutboxFollowUpMigrationTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 14, 12, 0);

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V13까지만 적용
        flyway(dataSource, "13").migrate();

        // 2. 기존 데이터 — 결제 1건, 발행 대기 이벤트 1건, 발송 기능 도입 전에 저장된 알림 2건
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1'), "
                + "(2, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1)");
        jdbc.update("INSERT INTO orders (id, user_id, store_id, delivery_address, status, total_price) "
                + "VALUES (1, 2, 1, '서울시', 'PAYMENT_COMPLETED', 10000)");
        jdbc.update("INSERT INTO payments (id, order_id, amount, fee_rate, fee_amount, settlement_amount, "
                + "payment_method, status, paid_at) VALUES (1, 1, 10000, 0.05, 500, 9500, 'CARD', 'COMPLETED', ?)", AT);
        jdbc.update("INSERT INTO outbox_events (id, event_id, aggregate_type, aggregate_id, event_type, payload, status, "
                + "attempt_count, next_attempt_at, created_at) VALUES (1, ?, 'ORDER', 1, 'PAYMENT_COMPLETED', '{}', "
                + "'PENDING', 0, ?, ?)", UUID.randomUUID(), AT, AT);
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, event_id, event_type, recipient_user_id, order_id, message, created_at) "
                + "VALUES (1001, ?, 'PAYMENT_COMPLETED', 2, 1, '결제 완료', ?), (1002, ?, 'PAYMENT_COMPLETED', 1, 1, '새 주문', ?)",
                eventId, AT, eventId, AT.plusMinutes(1));

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

    private static void insertNotification(String sendStatus, int attemptCount, LocalDateTime sentAt) {
        jdbc.update("INSERT INTO notifications (event_id, event_type, recipient_user_id, order_id, message, created_at, "
                        + "send_status, send_attempt_count, next_send_at, sent_at) "
                        + "VALUES (?, 'PAYMENT_COMPLETED', 2, 1, '결제 완료', ?, ?, ?, ?, ?)",
                UUID.randomUUID(), AT, sendStatus, attemptCount, AT, sentAt);
    }

    @Test
    void 기존_알림은_발송_대상에서_제외되고_내용은_바뀌지_않는다() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT message, send_status, send_attempt_count, next_send_at, created_at, sent_at "
                        + "FROM notifications WHERE id IN (1001, 1002) ORDER BY id");

        assertThat(rows).extracting(row -> row.get("message")).containsExactly("결제 완료", "새 주문");
        assertThat(rows).allSatisfy(row -> {
            // 발송 기능 도입 전 기록 → 뒤늦게 발송되지 않도록 SKIPPED
            assertThat(row.get("send_status")).isEqualTo("SKIPPED");
            assertThat(row.get("send_attempt_count")).isEqualTo(0);
            assertThat(row.get("next_send_at")).isEqualTo(row.get("created_at"));
            assertThat(row.get("sent_at")).isNull();
        });
    }

    @Test
    void 기존_결제와_이벤트는_바뀌지_않고_멱등성_기록은_만들어지지_않는다() {
        assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE id = 1", Long.class)).isEqualTo(10000L);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = 1", String.class))
                .isEqualTo("PENDING");
        // 과거 결제의 키는 알 수 없으므로 채워 넣지 않음
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_idempotency_keys", Integer.class)).isZero();
    }

    @Test
    void 새_알림은_발송_상태를_반드시_지정해야_한다() {
        // 기본값을 없앴으므로 send_status 없이 넣으면 NOT NULL 위반 (애플리케이션이 항상 값을 지정)
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO notifications (event_id, event_type, recipient_user_id, order_id, message, created_at, "
                        + "send_attempt_count, next_send_at) VALUES (?, 'PAYMENT_COMPLETED', 2, 1, '결제 완료', ?, 0, ?)",
                UUID.randomUUID(), AT, AT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 알림_발송_상태_제약이_어긋난_값을_거부한다() {
        insertNotification("PENDING", 0, null);
        insertNotification("SENT", 0, AT);
        insertNotification("FAILED", 5, null);

        // 정의되지 않은 상태
        assertThatThrownBy(() -> insertNotification("DONE", 0, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 발송 완료인데 발송 시각 없음 / 발송 전인데 발송 시각 있음
        assertThatThrownBy(() -> insertNotification("SENT", 0, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertNotification("PENDING", 0, AT))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 음수 실패 횟수
        assertThatThrownBy(() -> insertNotification("PENDING", -1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 멱등성_기록은_사용자별_키와_결제마다_하나만_저장된다() {
        jdbc.update("INSERT INTO orders (id, user_id, store_id, delivery_address, status, total_price) "
                + "VALUES (2, 2, 1, '서울시', 'PAYMENT_COMPLETED', 10000)");
        jdbc.update("INSERT INTO payments (id, order_id, amount, fee_rate, fee_amount, settlement_amount, "
                + "payment_method, status, paid_at) VALUES (2, 2, 10000, 0.05, 500, 9500, 'CARD', 'COMPLETED', ?)", AT);
        String insert = "INSERT INTO payment_idempotency_keys (user_id, idempotency_key, fingerprint, payment_id, created_at) "
                + "VALUES (?, ?, 'fp', ?, ?)";
        jdbc.update(insert, 2, "key-1", 1, AT);

        // 같은 사용자·같은 키 / 같은 결제에 다른 키 / 없는 결제
        assertThatThrownBy(() -> jdbc.update(insert, 2, "key-1", 2, AT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, 2, "key-2", 1, AT))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, 2, "key-3", 999, AT))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 다른 사용자는 같은 키를 쓸 수 있음
        jdbc.update(insert, 1, "key-1", 2, AT);
    }

    @Test
    void 조회_정리용_인덱스가_만들어진다() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

        assertThat(indexes).contains(
                "idx_payment_idempotency_keys_created_at",
                "idx_outbox_events_failed",
                "idx_outbox_events_pending_aggregate",
                "idx_outbox_events_published_at",
                "idx_consumed_events_consumed_at",
                "idx_notifications_send_pending",
                "idx_notifications_recipient");
    }
}
