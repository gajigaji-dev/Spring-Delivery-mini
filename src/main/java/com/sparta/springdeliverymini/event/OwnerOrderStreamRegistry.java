package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.OwnerStreamProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// 사장님 실시간 주문 알림(SSE, #76)의 연결 보관·전송
//
// - 연결은 이 서버 메모리에만 보관 (단일 인스턴스 기준). 서버가 여러 대면 알림을 저장한 인스턴스와
//   사장님이 연결된 인스턴스가 다를 수 있음 → Redis Pub/Sub 등으로 모든 인스턴스에 알리는 구조가 필요 (docs/architecture.md 실시간 알림 장)
// - 전송은 알림 저장 트랜잭션이 커밋된 뒤(AFTER_COMMIT), 전용 스레드 하나에서
//   · 커밋 전에 보내면 롤백된 알림(Kafka 재시도 대상)이 화면에 먼저 뜰 수 있음
//   · 소켓 쓰기는 느린 클라이언트에서 막힐 수 있어 Kafka 소비 스레드에서 직접 하지 않음
//   · 대기열이 가득 차면 버림 — 실시간 알림은 보조 수단이고, 알림 기록은 DB에 있으며 화면은 재연결·주기 조회로 따라잡음
// - 전송 실패(연결 끊김)는 그 연결만 정리하고 다른 연결에는 계속 보냄
@Slf4j
@Component
public class OwnerOrderStreamRegistry implements SmartLifecycle {

    public static final String CONNECTIONS_METRIC = "owner.stream.connections";
    public static final String DROPPED_METRIC = "owner.stream.events.dropped";

    private final OwnerStreamProperties properties;
    private final Map<Long, List<Connection>> connectionsByUser = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final ThreadPoolExecutor sender;
    private final Counter droppedCounter;
    private volatile boolean running;

    public OwnerOrderStreamRegistry(OwnerStreamProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.sender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.sendQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "owner-stream-sender");
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> onDropped());
        Gauge.builder(CONNECTIONS_METRIC, connectionCount, AtomicInteger::get)
                .description("열려 있는 사장님 실시간 주문 알림(SSE) 연결 수 (이 인스턴스)")
                .register(meterRegistry);
        this.droppedCounter = Counter.builder(DROPPED_METRIC)
                .description("전송 대기열이 가득 차 보내지 못한 실시간 주문 알림 수")
                .register(meterRegistry);
    }

    // 새 연결 등록 — storeId가 null이면 그 사장님의 모든 매장, 있으면 그 매장 알림만
    // 상한을 넘으면 가장 오래된 연결을 닫음 (OwnerStreamProperties 주석)
    public SseEmitter register(Long userId, Long storeId, SseEmitter emitter) {
        Connection connection = new Connection(userId, storeId, emitter);
        emitter.onCompletion(() -> remove(connection));
        emitter.onTimeout(() -> {
            // 정상 종료로 끝냄 → 클라이언트는 새 토큰으로 다시 연결 (타임아웃 예외 응답을 만들지 않음)
            remove(connection);
            emitter.complete();
        });
        emitter.onError(e -> remove(connection));

        List<Connection> evicted = new ArrayList<>();
        connectionsByUser.compute(userId, (id, list) -> {
            List<Connection> current = list == null ? new CopyOnWriteArrayList<>() : list;
            current.add(connection);
            while (current.size() > properties.maxConnectionsPerUser()) {
                evicted.add(current.removeFirst());
            }
            return current;
        });
        connectionCount.addAndGet(1 - evicted.size());
        evicted.forEach(c -> c.emitter().complete());

        // 연결 직후 한 번 보냄 — 응답 헤더를 바로 내려보내 클라이언트가 "연결됨"을 알 수 있게 함
        // (첫 데이터가 나가기 전까지는 fetch가 응답을 받지 못해 연결 중인지 실패인지 구분할 수 없음)
        send(connection, SseEmitter.event().name("connected").data("ok"));
        return emitter;
    }

    @TransactionalEventListener
    public void onOwnerOrderNotified(OwnerOrderNotified event) {
        sender.execute(() -> deliver(event));
    }

    // 하트비트 — config.BackgroundTaskConfig에서 owner-stream.heartbeat 간격으로 실행
    // 끊긴 연결은 쓰기 실패로 발견되어 정리됨 (클라이언트가 말없이 사라져도 서버는 쓰기 전까지 모름)
    public void sendHeartbeat() {
        for (List<Connection> list : connectionsByUser.values()) {
            for (Connection connection : list) {
                send(connection, SseEmitter.event().comment("ping"));
            }
        }
    }

    public int connectionCount() {
        return connectionCount.get();
    }

    void deliver(OwnerOrderNotified event) {
        List<Connection> list = connectionsByUser.get(event.ownerId());
        if (list == null) {
            return;
        }
        for (Connection connection : list) {
            if (connection.accepts(event.payload().storeId())) {
                send(connection, SseEmitter.event()
                        .name("order")
                        .id(String.valueOf(event.payload().notificationId()))
                        .data(event.payload(), MediaType.APPLICATION_JSON));
            }
        }
    }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        try {
            connection.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            // IOException: 클라이언트가 끊음 / IllegalStateException: 이미 완료된 연결
            log.debug("실시간 알림 전송 실패 → 연결 정리 userId={}, reason={}", connection.userId(), e.toString());
            remove(connection);
        }
    }

    private void remove(Connection connection) {
        boolean[] removed = {false};
        connectionsByUser.computeIfPresent(connection.userId(), (id, list) -> {
            removed[0] = list.remove(connection);
            return list.isEmpty() ? null : list;
        });
        if (removed[0]) {
            connectionCount.decrementAndGet();
        }
    }

    private void onDropped() {
        droppedCounter.increment();
        log.warn("실시간 주문 알림 전송 대기열이 가득 차 알림을 버림 (화면은 재연결·주기 조회로 갱신)");
    }

    @Override
    public void start() {
        running = true;
    }

    // 종료 시 열린 연결을 정상 종료 → 클라이언트는 재연결을 시도하다 다른(재기동된) 서버에 붙음
    // 기본 phase(가장 늦게 시작·가장 먼저 종료)라 웹 서버 graceful shutdown보다 먼저 실행됨
    // → 열린 SSE 요청 때문에 graceful shutdown이 제한 시간(30초)을 다 기다리지 않음
    @Override
    public void stop() {
        running = false;
        sender.shutdownNow();
        connectionsByUser.values().forEach(list -> list.forEach(c -> {
            try {
                c.emitter().complete();
            } catch (RuntimeException ignored) {
                // 이미 끊긴 연결
            }
        }));
        connectionsByUser.clear();
        connectionCount.set(0);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // storeId == null: 그 사장님의 모든 매장 (구독 후 새로 만든 매장 포함)
    // 이벤트의 ownerId는 결제 시점 매장 주인이라, 사용자 기준으로 먼저 고른 뒤 매장으로 한 번 더 거름
    private record Connection(Long userId, Long storeId, SseEmitter emitter) {

        boolean accepts(Long eventStoreId) {
            return storeId == null || storeId.equals(eventStoreId);
        }

        // 같은 사용자·매장의 연결이 여러 개일 수 있으므로 값이 아니라 객체 자체로 구분
        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
