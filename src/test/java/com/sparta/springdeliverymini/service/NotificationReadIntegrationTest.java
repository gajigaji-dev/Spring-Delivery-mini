package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.Notification;
import com.sparta.springdeliverymini.entity.NotificationSendStatus;
import com.sparta.springdeliverymini.entity.OutboxEventType;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.NotificationRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 알림 읽음 처리·안 읽은 개수(V15)를 실제 PostgreSQL + 보안 필터까지 통과시켜 검증
// - 본인 알림만 읽음 처리(다른 사람 403, 없음 404), 이미 읽은 알림도 204이고 처음 읽은 시각 유지
// - 목록 응답의 read·readAt, 안 읽은 개수는 본인 것만
// - 읽음 처리와 발송 상태 변경이 서로의 컬럼을 덮어쓰지 않음
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class NotificationReadIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;

    private User customer;
    private User other;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        customer = userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        other = userRepository.save(new User("customer2", "encoded-password", Role.CUSTOMER));
    }

    @AfterEach
    void tearDown() {
        // 알림은 users에 FK가 없어 순서 무관
        notificationRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createToken(user.getUsername(), user.getRole());
    }

    private Notification notificationFor(User recipient) {
        return notificationRepository.save(new Notification(UUID.randomUUID(), OutboxEventType.PAYMENT_COMPLETED,
                recipient.getId(), 1L, "주문 #1 결제가 완료되었습니다.", NOW));
    }

    private LocalDateTime readAtOf(Notification notification) {
        return jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?", LocalDateTime.class,
                notification.getId());
    }

    @Test
    void 본인_알림을_읽음_처리하면_읽은_시각이_기록되고_목록에_읽음으로_보인다() throws Exception {
        Notification notification = notificationFor(customer);
        clock.set(NOW.plusMinutes(5));

        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNoContent());

        assertThat(readAtOf(notification)).isEqualTo(NOW.plusMinutes(5));
        mockMvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].read").value(true))
                .andExpect(jsonPath("$[0].readAt").value("2026-11-20T12:05:00"));
    }

    @Test
    void 안_읽은_알림은_목록에서_read가_false이고_readAt이_null이다() throws Exception {
        notificationFor(customer);

        mockMvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].read").value(false))
                .andExpect(jsonPath("$[0].readAt").doesNotExist());
    }

    @Test
    void 이미_읽은_알림을_다시_읽음_처리해도_204이고_처음_읽은_시각이_유지된다() throws Exception {
        Notification notification = notificationFor(customer);
        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNoContent());
        clock.set(NOW.plusHours(1));

        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNoContent());

        assertThat(readAtOf(notification)).isEqualTo(NOW);
    }

    @Test
    void 다른_사람의_알림은_읽음_처리할_수_없다_403() throws Exception {
        Notification notification = notificationFor(other);

        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("본인의 알림만 읽음 처리할 수 있습니다."));

        assertThat(readAtOf(notification)).isNull();
    }

    @Test
    void 없는_알림은_404() throws Exception {
        mockMvc.perform(patch("/api/notifications/{id}/read", 999_999L)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 토큰이_없으면_읽음_처리와_개수_조회_모두_401() throws Exception {
        Notification notification = notificationFor(customer);

        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        assertThat(readAtOf(notification)).isNull();
    }

    @Test
    void 안_읽은_개수는_본인_알림_중_읽지_않은_것만_센다() throws Exception {
        Notification read = notificationFor(customer);
        notificationFor(customer);
        notificationFor(customer);
        notificationFor(other);
        mockMvc.perform(patch("/api/notifications/{id}/read", read.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));
        mockMvc.perform(get("/api/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void 읽음_처리_뒤_발송_상태를_바꿔도_읽은_시각은_지워지지_않는다() throws Exception {
        Notification notification = notificationFor(customer);
        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNoContent());

        // 발송기처럼 엔티티를 읽어 발송 완료로 바꿈 — 엔티티 저장은 모든 컬럼을 UPDATE하지만 read_at은 대상에서 빠져 있음
        transactionTemplate.executeWithoutResult(tx ->
                notificationRepository.findById(notification.getId()).orElseThrow().markSent(NOW.plusSeconds(5)));

        assertThat(readAtOf(notification)).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT send_status FROM notifications WHERE id = ?", String.class,
                notification.getId())).isEqualTo(NotificationSendStatus.SENT.name());
    }

    @Test
    void 읽음_처리는_발송_상태를_바꾸지_않는다() throws Exception {
        Notification notification = notificationFor(customer);

        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNoContent());

        // 읽었어도 발송 대기는 그대로 (읽음과 발송은 별개)
        assertThat(jdbc.queryForObject("SELECT send_status FROM notifications WHERE id = ?", String.class,
                notification.getId())).isEqualTo(NotificationSendStatus.PENDING.name());
    }
}
