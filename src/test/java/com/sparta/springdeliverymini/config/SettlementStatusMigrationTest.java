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

// V8(정산 PENDING 상태, ADMIN 역할 추가)이 V7 정산 데이터가 있는 DB에서도 안전하게 적용되는지 검증
// V7까지 적용한 DB에 CONFIRMED·PAID 정산을 넣고 → V8을 적용한 뒤 기존 행이 그대로인지, 새 상태·역할을 쓸 수 있는지 확인
class SettlementStatusMigrationTest {

    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 6, 1, 10);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 7, 10, 0);

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V7까지만 적용 (PENDING·ADMIN 없음, confirmed_at NOT NULL)
        flyway(dataSource, "7").migrate();

        // 2. 기존 데이터 — V7 스케줄러가 만든 CONFIRMED 정산, 그중 하나는 지급 완료
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1)");
        jdbc.update("INSERT INTO settlements (id, store_id, settlement_date, payment_count, total_amount, total_fee, "
                        + "settlement_amount, status, confirmed_at, paid_at) VALUES "
                        + "(1, 1, '2026-11-05', 1, 10000, 500, 9500, 'CONFIRMED', ?, NULL), "
                        + "(2, 1, '2026-11-06', 1, 10000, 500, 9500, 'PAID', ?, ?)",
                CONFIRMED_AT, CONFIRMED_AT, PAID_AT);

        // 3. V8까지 적용 (V9 이후 변경은 PaymentRefundMigrationTest에서 검증)
        flyway(dataSource, "8").migrate();
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
    void 기존_정산은_상태와_시각이_그대로_유지된다() {
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id = 1", String.class)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM settlements WHERE id = 1", LocalDateTime.class))
                .isEqualTo(CONFIRMED_AT);
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id = 2", String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT paid_at FROM settlements WHERE id = 2", LocalDateTime.class))
                .isEqualTo(PAID_AT);
    }

    @Test
    void 확정_시각_없는_PENDING_정산을_저장할_수_있다() {
        // 위에서 id를 직접 넣어 identity 시퀀스가 움직이지 않았으므로 id도 직접 지정
        jdbc.update("INSERT INTO settlements (id, store_id, settlement_date, payment_count, total_amount, total_fee, "
                + "settlement_amount, status) VALUES (3, 1, '2026-11-01', 0, 0, 0, 0, 'PENDING')");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlements WHERE status = 'PENDING' AND confirmed_at IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 확정_시각_없는_CONFIRMED_정산은_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET confirmed_at = NULL WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlements_confirmed_at_check");
    }

    @Test
    void ADMIN_역할을_저장할_수_있다() {
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (10, 'pw', 'ADMIN', 'admin')");

        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE username = 'admin'", String.class))
                .isEqualTo("ADMIN");
    }

    @Test
    void 정의되지_않은_역할은_여전히_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (id, password, role, username) VALUES (11, 'pw', 'ROOT', 'root')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("users_role_check");
    }
}
