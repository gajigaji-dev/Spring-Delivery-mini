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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V15(알림 읽음 시각, Refresh Token 테이블)가 V14 데이터가 있는 DB에서도 안전하게 적용되는지,
// 기존 알림은 안 읽음(NULL)으로 남고 다른 값은 바뀌지 않는지, 새 제약이 잘못된 값을 거부하는지 검증
class NotificationReadRefreshTokenMigrationTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 19, 12, 0);

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V14까지만 적용
        flyway(dataSource, "14").migrate();

        // 2. 기존 데이터 — 회원 2명, 발송 상태가 서로 다른 알림 3건
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1'), "
                + "(2, 'pw', 'CUSTOMER', 'customer1')");
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, event_id, event_type, recipient_user_id, order_id, message, created_at, "
                        + "send_status, send_attempt_count, next_send_at, sent_at) VALUES "
                        + "(1001, ?, 'PAYMENT_COMPLETED', 2, 1, '결제 완료', ?, 'SENT', 0, ?, ?), "
                        + "(1002, ?, 'PAYMENT_COMPLETED', 1, 1, '새 주문', ?, 'PENDING', 0, ?, NULL), "
                        + "(1003, ?, 'DELIVERY_COMPLETED', 2, 1, '배달 완료', ?, 'SKIPPED', 0, ?, NULL)",
                eventId, AT, AT, AT.plusSeconds(5),
                eventId, AT, AT,
                UUID.randomUUID(), AT, AT);

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

    private static void insertToken(String hash, LocalDateTime revokedAt, String reason) {
        // family_expires_at(V16)은 latest에서 필수
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, expires_at, family_expires_at, revoked_at, "
                + "revoke_reason) VALUES (2, ?, ?, ?, ?, ?, ?, ?)", hash, UUID.randomUUID(), AT, AT.plusDays(14), AT.plusDays(30),
                revokedAt, reason);
    }

    @Test
    void 기존_알림은_안_읽음으로_남고_다른_값은_바뀌지_않는다() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT message, send_status, read_at FROM notifications WHERE id IN (1001, 1002, 1003) ORDER BY id");

        assertThat(rows).extracting(row -> row.get("message")).containsExactly("결제 완료", "새 주문", "배달 완료");
        assertThat(rows).extracting(row -> row.get("send_status")).containsExactly("SENT", "PENDING", "SKIPPED");
        assertThat(rows).allSatisfy(row -> assertThat(row.get("read_at")).isNull());
        // 받는 사람별 안 읽은 개수 — 기존 알림이 모두 안 읽음으로 잡힘
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = 2 AND read_at IS NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void 안_읽은_알림_부분_인덱스와_Refresh_Token_계열_인덱스가_만들어진다() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname IN "
                        + "('idx_notifications_recipient_unread', 'idx_refresh_tokens_family_active') ORDER BY indexname",
                String.class);

        assertThat(indexes).hasSize(2);
        assertThat(indexes.get(0)).contains("WHERE (read_at IS NULL)");
        assertThat(indexes.get(1)).contains("WHERE (revoked_at IS NULL)");
    }

    @Test
    void Refresh_Token은_유효한_값이면_저장된다() {
        assertThatCode(() -> {
            insertToken("a".repeat(64), null, null);
            insertToken("b".repeat(64), AT.plusHours(1), "ROTATED");
            insertToken("c".repeat(64), AT.plusHours(1), "LOGOUT");
            insertToken("d".repeat(64), AT.plusHours(1), "REUSE_DETECTED");
        }).doesNotThrowAnyException();
    }

    @Test
    void Refresh_Token_제약이_어긋난_값을_거부한다() {
        insertToken("e".repeat(64), null, null);

        // 같은 해시 두 번
        assertThatThrownBy(() -> insertToken("e".repeat(64), null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 폐기 시각과 사유는 둘 다 있거나 둘 다 없음
        assertThatThrownBy(() -> insertToken("f".repeat(64), AT, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertToken("g".repeat(64), null, "LOGOUT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 정해진 사유만
        assertThatThrownBy(() -> insertToken("h".repeat(64), AT, "EXPIRED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 없는 회원
        assertThatThrownBy(() -> jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, "
                + "expires_at, family_expires_at) VALUES (999, ?, ?, ?, ?, ?)", "i".repeat(64), UUID.randomUUID(), AT, AT.plusDays(1),
                AT.plusDays(30)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 만료 시각은 생성 시각 뒤
        assertThatThrownBy(() -> jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, "
                + "expires_at, family_expires_at) VALUES (2, ?, ?, ?, ?, ?)", "j".repeat(64), UUID.randomUUID(), AT, AT,
                AT.plusDays(30)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
