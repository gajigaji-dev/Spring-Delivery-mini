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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V11(정산 상태 변경 이력, 집계 실패 기록)이 V10 데이터가 있는 DB에서도 안전하게 적용되는지,
// 기존 정산을 건드리지 않는지, 새 제약이 잘못된 이력·실패 기록을 거부하는지 검증
class SettlementAuditMigrationTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 6, 9, 0);

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V10까지만 적용
        flyway(dataSource, "10").migrate();

        // 2. 기존 데이터 — 관리자, 매장 2개, 상태별 정산 3건 (1: PAID, 2: PENDING, 3: CONFIRMED)
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES (1, 'pw', 'OWNER', 'owner1'), "
                + "(2, 'pw', 'ADMIN', 'admin')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1), (2, '피자집', 1)");
        jdbc.update("INSERT INTO settlements (id, store_id, settlement_date, payment_count, total_amount, total_fee, "
                        + "sales_settlement_amount, settlement_amount, status, confirmed_at, paid_at) VALUES "
                        + "(1, 1, '2026-11-04', 1, 10000, 500, 9500, 9500, 'PAID', ?, ?), "
                        + "(2, 1, '2026-11-05', 1, 10000, 500, 9500, 9500, 'PENDING', NULL, NULL), "
                        + "(3, 2, '2026-11-05', 1, 10000, 500, 9500, 9500, 'CONFIRMED', ?, NULL)",
                AT.minusDays(1), AT, AT);

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

    private static void insertHistory(long settlementId, String from, String to, Long actorId) {
        jdbc.update("INSERT INTO settlement_status_histories (settlement_id, from_status, to_status, actor_user_id, changed_at) "
                + "VALUES (?, ?, ?, ?, ?)", settlementId, from, to, actorId, AT);
    }

    @Test
    void 기존_정산은_바뀌지_않고_과거_이력은_만들어지지_않는다() {
        Map<String, Object> paid = jdbc.queryForMap("SELECT status, settlement_amount, paid_at FROM settlements WHERE id = 1");
        assertThat(paid.get("status")).isEqualTo("PAID");
        assertThat(paid.get("settlement_amount")).isEqualTo(9500L);
        assertThat(paid.get("paid_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Integer.class)).isEqualTo(3);

        // 처리자를 알 수 없는 과거 전이는 채워 넣지 않음
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_status_histories WHERE settlement_id = 1",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_aggregation_failures WHERE store_id = 2",
                Integer.class)).isZero();
    }

    @Test
    void 기존_정산의_이후_전이는_이력으로_저장할_수_있다() {
        // V11 전에 확정된 정산(3)을 V11 이후에 지급
        insertHistory(3, "CONFIRMED", "PAID", 2L);

        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM settlement_status_histories "
                + "WHERE settlement_id = 3 AND to_status = 'PAID'", Long.class)).isEqualTo(2L);
    }

    @Test
    void 스케줄러_집계_이력은_처리자_없이_저장되지만_확정_지급_이력은_처리자가_없으면_거부한다() {
        insertHistory(2, null, "PENDING", null);

        assertThatThrownBy(() -> insertHistory(2, "PENDING", "CONFIRMED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlement_status_histories_actor_check");
        assertThatThrownBy(() -> insertHistory(1, "CONFIRMED", "PAID", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlement_status_histories_actor_check");
    }

    @Test
    void 허용되지_않는_전이_이력은_거부한다() {
        // 단계 건너뛰기, 되돌리기, 이전 상태 없는 확정, 없는 상태
        String[][] invalid = {{"PENDING", "PAID"}, {"PAID", "CONFIRMED"}, {null, "CONFIRMED"}, {"PENDING", "PENDING"},
                {"CONFIRMED", "DONE"}};
        for (String[] transition : invalid) {
            assertThatThrownBy(() -> insertHistory(1, transition[0], transition[1], 2L))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("settlement_status_histories_transition_check");
        }
    }

    @Test
    void 같은_정산의_같은_전이_이력은_한_번만_저장된다() {
        // 정산 1은 "과거 이력 없음" 검증에 쓰므로 정산 3 사용 (테스트끼리 DB를 함께 씀)
        insertHistory(3, "PENDING", "CONFIRMED", 2L);

        assertThatThrownBy(() -> insertHistory(3, "PENDING", "CONFIRMED", 2L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_settlement_status_histories_settlement_to");
    }

    @Test
    void 없는_정산이나_없는_회원의_이력은_거부한다() {
        assertThatThrownBy(() -> insertHistory(999, null, "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_settlement_status_histories_settlement");
        assertThatThrownBy(() -> insertHistory(2, "PENDING", "CONFIRMED", 999L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_settlement_status_histories_actor");
    }

    @Test
    void 실패_기록은_매장_날짜당_1건이고_상태와_해결_정보가_어긋나면_거부한다() {
        String insert = "INSERT INTO settlement_aggregation_failures (store_id, settlement_date, status, failure_count, "
                + "last_error, first_failed_at, last_failed_at, resolved_at, resolved_by_user_id, settlement_id) "
                + "VALUES (1, ?::date, ?, ?, 'error', ?, ?, ?, ?, ?)";

        // 정상: FAILED, 그리고 정산과 함께 RESOLVED
        jdbc.update(insert, "2026-11-06", "FAILED", 1, AT, AT, null, null, null);
        jdbc.update(insert, "2026-11-07", "RESOLVED", 2, AT, AT, AT, 2L, 1L);

        assertThatThrownBy(() -> jdbc.update(insert, "2026-11-06", "FAILED", 1, AT, AT, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_settlement_aggregation_failures_store_date");
        assertThatThrownBy(() -> jdbc.update(insert, "2026-11-08", "RETRYING", 1, AT, AT, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlement_aggregation_failures_status_check");
        assertThatThrownBy(() -> jdbc.update(insert, "2026-11-08", "FAILED", 0, AT, AT, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlement_aggregation_failures_count_check");
        // FAILED인데 해결 시각이 있음 / RESOLVED인데 해결 시각이 없음
        assertThatThrownBy(() -> jdbc.update(insert, "2026-11-08", "FAILED", 1, AT, AT, AT, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlement_aggregation_failures_resolved_at_check");
        assertThatThrownBy(() -> jdbc.update(insert, "2026-11-08", "RESOLVED", 1, AT, AT, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlement_aggregation_failures_resolved_at_check");
        // FAILED인데 연결된 정산이 있음
        assertThatThrownBy(() -> jdbc.update(insert, "2026-11-08", "FAILED", 1, AT, AT, null, null, 1L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlement_aggregation_failures_resolved_info_check");
    }
}
