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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V16(Refresh Token 계열 절대 만료, 정리용 인덱스)가 V15 토큰이 있는 DB에서도 안전하게 적용되는지 검증
// - 기존 계열은 첫 발급 + 30일로 백필하되, 이미 그보다 늦게 만료되는 토큰은 앞당기지 않음
// - 같은 계열의 행은 모두 같은 값, 기존 만료 시각·폐기 상태는 그대로
class RefreshTokenFamilyExpiryMigrationTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final UUID SHORT_FAMILY = UUID.randomUUID();
    private static final UUID LONG_FAMILY = UUID.randomUUID();

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V15까지만 적용
        flyway(dataSource, "15").migrate();

        // 2. 기존 토큰
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'CUSTOMER', 'customer1')");
        // 계열 A: 로그인 직후 1번 재발급 — 모든 행이 첫 발급 + 30일 안에 만료
        insert(1, SHORT_FAMILY, AT, AT.plusDays(14), AT.plusDays(1), "ROTATED");
        insert(2, SHORT_FAMILY, AT.plusDays(1), AT.plusDays(15), null, null);
        // 계열 B: V15에는 절대 만료가 없어 25일째 재발급한 토큰이 39일째 만료 — 첫 발급 + 30일보다 늦음
        insert(3, LONG_FAMILY, AT, AT.plusDays(14), AT.plusDays(10), "ROTATED");
        insert(4, LONG_FAMILY, AT.plusDays(10), AT.plusDays(24), AT.plusDays(25), "ROTATED");
        insert(5, LONG_FAMILY, AT.plusDays(25), AT.plusDays(39), null, null);

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

    private static void insert(long id, UUID familyId, LocalDateTime createdAt, LocalDateTime expiresAt,
                               LocalDateTime revokedAt, String reason) {
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, created_at, expires_at, revoked_at, "
                        + "revoke_reason) VALUES (?, 1, ?, ?, ?, ?, ?, ?)",
                id, String.valueOf(id).repeat(64).substring(0, 64), familyId, createdAt, expiresAt, revokedAt, reason);
    }

    private static List<LocalDateTime> familyExpiresAt(UUID familyId) {
        return jdbc.queryForList("SELECT family_expires_at FROM refresh_tokens WHERE family_id = ? ORDER BY id",
                LocalDateTime.class, familyId);
    }

    @Test
    void 기존_계열은_첫_발급_후_30일로_백필된다() {
        assertThat(familyExpiresAt(SHORT_FAMILY)).containsOnly(AT.plusDays(30)).hasSize(2);
    }

    @Test
    void 이미_더_늦게_만료되는_토큰이_있는_계열은_그_시각까지로_백필되어_앞당겨지지_않는다() {
        assertThat(familyExpiresAt(LONG_FAMILY)).containsOnly(AT.plusDays(39)).hasSize(3);
    }

    @Test
    void 기존_만료_시각과_폐기_상태는_바뀌지_않는다() {
        assertThat(jdbc.queryForList("SELECT expires_at FROM refresh_tokens ORDER BY id", LocalDateTime.class))
                .containsExactly(AT.plusDays(14), AT.plusDays(15), AT.plusDays(14), AT.plusDays(24), AT.plusDays(39));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void 새_행은_계열_절대_만료가_필수이고_개별_만료가_그_시각을_넘을_수_없다() {
        // NOT NULL
        assertThatThrownBy(() -> jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, "
                        + "expires_at) VALUES (1, ?, ?, ?, ?)", "x".repeat(64), UUID.randomUUID(), AT, AT.plusDays(14)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // expires_at <= family_expires_at
        assertThatThrownBy(() -> jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, "
                        + "expires_at, family_expires_at) VALUES (1, ?, ?, ?, ?, ?)",
                "y".repeat(64), UUID.randomUUID(), AT, AT.plusDays(14), AT.plusDays(7)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 정리용_만료_시각_인덱스가_만들어진다() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_refresh_tokens_expires_at'", Integer.class))
                .isEqualTo(1);
    }
}
