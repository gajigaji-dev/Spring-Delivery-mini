package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.OutboxEventType;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.event.DeliveryEventMessage;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 사장님 실시간 주문 알림(SSE, #76)을 실제 서버(임의 포트) + 보안 필터 + PostgreSQL로 검증
// - 알림 저장(NotificationService.handle) 커밋 후 접속 중인 사장님에게만 order 이벤트 전달
// - 인증 없음 401, OWNER 아님(토큰·DB 기준) 403, 다른 사장님 매장 403, 없는 매장 404
// - 연결이 열려 있는 동안 DB 커넥션을 잡지 않음 (open-in-view 함정, OwnerOrderStreamService 주석)
// Kafka는 끈 상태라 Consumer 대신 NotificationService.handle을 직접 호출
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OwnerOrderStreamIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @LocalServerPort private int port;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<Stream<String>> openStreams = new ArrayList<>();

    private User owner;
    private User otherOwner;
    private User customer;
    private Store store;
    private Store otherStore;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(new User("owner76", "encoded-password", Role.OWNER));
        otherOwner = userRepository.save(new User("owner76b", "encoded-password", Role.OWNER));
        customer = userRepository.save(new User("customer76", "encoded-password", Role.CUSTOMER));
        store = storeRepository.save(new Store("실시간매장", owner));
        otherStore = storeRepository.save(new Store("다른매장", otherOwner));
    }

    @AfterEach
    void tearDown() {
        // 열린 SSE 연결을 먼저 닫음 → 서버 쪽 연결 정리 후 데이터 삭제
        openStreams.forEach(Stream::close);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM consumed_events");
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void 결제_완료_알림을_저장하면_접속_중인_사장님에게_새_주문_이벤트가_전달된다() throws Exception {
        BlockingQueue<String> mine = subscribe(owner, null);
        BlockingQueue<String> others = subscribe(otherOwner, null);
        awaitLine(mine, "event:connected");
        awaitLine(others, "event:connected");

        notificationService.handle(paymentCompleted(77L));

        awaitLine(mine, "event:order");
        String data = awaitLineStartingWith(mine, "data:");
        assertThat(data).contains("\"orderId\":77", "\"storeId\":" + store.getId(), "\"type\":\"PAYMENT_COMPLETED\"",
                "\"amount\":15000");
        // 다른 사장님 연결에는 오지 않음
        assertThat(pollLine(others, "event:order", Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void 매장을_지정해_구독하면_그_매장_알림만_받는다() throws Exception {
        Store secondStore = storeRepository.save(new Store("두번째매장", owner));
        BlockingQueue<String> onlySecond = subscribe(owner, secondStore.getId());
        awaitLine(onlySecond, "event:connected");

        notificationService.handle(paymentCompleted(78L)); // 첫 번째 매장 주문

        assertThat(pollLine(onlySecond, "event:order", Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void 연결이_열려_있는_동안_DB_커넥션을_잡지_않는다() throws Exception {
        BlockingQueue<String> lines = subscribe(owner, store.getId());
        awaitLine(lines, "event:connected");

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        // 다른 주기 작업이 잠깐 쓰는 커넥션과 구분하려고 0이 되는 순간이 있는지 확인
        // (요청 EntityManager가 커넥션을 잡고 있으면 연결이 끊길 때까지 계속 1 이상)
        long deadline = System.nanoTime() + WAIT.toNanos();
        int active;
        do {
            active = hikari.getHikariPoolMXBean().getActiveConnections();
        } while (active > 0 && System.nanoTime() < deadline && sleep());
        assertThat(active).isZero();
    }

    @Test
    void 토큰_없이_구독하면_401() throws Exception {
        assertThat(statusOf(null, null)).isEqualTo(401);
    }

    @Test
    void 고객_토큰으로_구독하면_403() throws Exception {
        assertThat(statusOf(bearer(customer, Role.CUSTOMER), null)).isEqualTo(403);
    }

    @Test
    void 토큰은_OWNER여도_DB_역할이_OWNER가_아니면_403() throws Exception {
        assertThat(statusOf(bearer(customer, Role.OWNER), null)).isEqualTo(403);
    }

    @Test
    void 다른_사장님의_매장을_지정하면_403_없는_매장은_404() throws Exception {
        assertThat(statusOf(bearer(owner, Role.OWNER), otherStore.getId())).isEqualTo(403);
        assertThat(statusOf(bearer(owner, Role.OWNER), 999_999L)).isEqualTo(404);
    }

    private DeliveryEventMessage paymentCompleted(long orderId) {
        return new DeliveryEventMessage(UUID.randomUUID(), OutboxEventType.PAYMENT_COMPLETED, orderId, 1L,
                store.getId(), customer.getId(), owner.getId(), 15_000L, LocalDateTime.of(2026, 11, 21, 12, 0));
    }

    private String bearer(User user, Role role) {
        return "Bearer " + jwtProvider.createToken(user.getUsername(), role);
    }

    private HttpRequest request(String authorization, Long storeId) {
        String query = storeId == null ? "" : "?storeId=" + storeId;
        // Accept는 브라우저 EventSource와 같은 값만 — 오류 응답이 406으로 바뀌지 않고 원래 상태 코드로 오는지도 함께 확인
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/owner/order-events" + query))
                .header("Accept", "text/event-stream")
                .timeout(WAIT);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder.GET().build();
    }

    private int statusOf(String authorization, Long storeId) throws Exception {
        return httpClient.send(request(authorization, storeId), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // 응답 줄을 별도 스레드에서 큐로 옮김 — 테스트는 큐에서 기다림
    private BlockingQueue<String> subscribe(User user, Long storeId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/owner/order-events"
                        + (storeId == null ? "" : "?storeId=" + storeId)))
                .header("Accept", "text/event-stream")
                .header("Authorization", bearer(user, Role.OWNER))
                .GET().build();
        HttpResponse<Stream<String>> response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("text/event-stream"));
        Stream<String> body = response.body();
        openStreams.add(body);
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                body.forEach(queue::add);
            } catch (RuntimeException ignored) {
                // 테스트 종료 시 스트림을 닫으면서 나는 예외
            }
        });
        reader.setDaemon(true);
        reader.start();
        return queue;
    }

    private static void awaitLine(BlockingQueue<String> queue, String expected) throws InterruptedException {
        assertThat(pollLine(queue, expected, WAIT)).as("'%s' 줄을 받아야 함", expected).isTrue();
    }

    private static boolean pollLine(BlockingQueue<String> queue, String expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            String line = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (line != null && line.equals(expected)) {
                return true;
            }
        }
    }

    private static String awaitLineStartingWith(BlockingQueue<String> queue, String prefix)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            String line = queue.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (line != null && line.startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("'" + prefix + "'로 시작하는 줄을 받지 못함");
    }

    private static boolean sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }
}
