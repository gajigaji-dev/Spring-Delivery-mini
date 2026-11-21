package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.OwnerStreamProperties;
import com.sparta.springdeliverymini.dto.OwnerOrderEventResponse;
import com.sparta.springdeliverymini.entity.OutboxEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 연결 보관·전송 규칙 — 받는 사장님·매장 범위, 연결 상한, 끊긴 연결 정리 (전송 스레드를 거치지 않고 deliver를 직접 호출)
class OwnerOrderStreamRegistryTest {

    private static final long OWNER = 1L;
    private static final long OTHER_OWNER = 2L;

    private SimpleMeterRegistry meterRegistry;
    private OwnerOrderStreamRegistry registry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        registry = new OwnerOrderStreamRegistry(
                new OwnerStreamProperties(Duration.ofMinutes(10), Duration.ofSeconds(25), 2, 10), meterRegistry);
    }

    @Test
    void 연결_직후_connected_이벤트를_보낸다() {
        RecordingEmitter emitter = connect(OWNER, null);

        assertThat(emitter.eventNames()).containsExactly("connected");
        assertThat(registry.connectionCount()).isEqualTo(1);
    }

    @Test
    void 주문_알림은_해당_사장님의_연결에만_보낸다() {
        RecordingEmitter mine = connect(OWNER, null);
        RecordingEmitter others = connect(OTHER_OWNER, null);

        registry.deliver(notified(OWNER, 10L));

        assertThat(mine.eventNames()).containsExactly("connected", "order");
        assertThat(others.eventNames()).containsExactly("connected");
    }

    @Test
    void 매장을_지정한_연결은_그_매장_알림만_받고_지정하지_않은_연결은_모든_매장_알림을_받는다() {
        RecordingEmitter store10 = connect(OWNER, 10L);
        RecordingEmitter all = connect(OWNER, null);

        registry.deliver(notified(OWNER, 10L));
        registry.deliver(notified(OWNER, 20L));

        assertThat(store10.eventNames()).containsExactly("connected", "order");
        assertThat(all.eventNames()).containsExactly("connected", "order", "order");
    }

    @Test
    void 연결_상한을_넘으면_가장_오래된_연결을_닫는다() {
        RecordingEmitter first = connect(OWNER, null);
        RecordingEmitter second = connect(OWNER, null);
        RecordingEmitter third = connect(OWNER, null);

        assertThat(first.completed).isTrue();
        assertThat(second.completed).isFalse();
        assertThat(third.completed).isFalse();
        assertThat(registry.connectionCount()).isEqualTo(2);
        assertThat(meterRegistry.get(OwnerOrderStreamRegistry.CONNECTIONS_METRIC).gauge().value()).isEqualTo(2.0);

        registry.deliver(notified(OWNER, 10L));
        assertThat(first.eventNames()).containsExactly("connected");
    }

    @Test
    void 전송에_실패한_연결은_정리하고_나머지_연결에는_계속_보낸다() {
        RecordingEmitter broken = connect(OWNER, null);
        RecordingEmitter healthy = connect(OWNER, null);
        broken.failOnSend = true;

        registry.deliver(notified(OWNER, 10L));

        assertThat(healthy.eventNames()).containsExactly("connected", "order");
        assertThat(registry.connectionCount()).isEqualTo(1);
    }

    @Test
    void 하트비트_전송에_실패한_연결은_정리된다() {
        RecordingEmitter broken = connect(OWNER, null);
        RecordingEmitter healthy = connect(OTHER_OWNER, null);
        broken.failOnSend = true;

        registry.sendHeartbeat();

        assertThat(registry.connectionCount()).isEqualTo(1);
        assertThat(healthy.sentCount()).isEqualTo(2); // connected + ping
    }

    private RecordingEmitter connect(long userId, Long storeId) {
        RecordingEmitter emitter = new RecordingEmitter();
        registry.register(userId, storeId, emitter);
        return emitter;
    }

    private static OwnerOrderNotified notified(long ownerId, long storeId) {
        return new OwnerOrderNotified(ownerId, new OwnerOrderEventResponse(100L, OutboxEventType.PAYMENT_COMPLETED,
                5L, storeId, 15_000L, "새 주문 #5의 결제가 완료되었습니다.", LocalDateTime.of(2026, 11, 21, 12, 0)));
    }

    // 실제 응답 없이 보낸 이벤트를 기록 — 이벤트 이름은 직렬화된 "event:" 줄에서 꺼냄
    static class RecordingEmitter extends SseEmitter {

        final List<String> sent = new ArrayList<>();
        boolean failOnSend;
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("연결 끊김");
            }
            StringBuilder text = new StringBuilder();
            builder.build().forEach(d -> text.append(d.getData()));
            sent.add(text.toString());
        }

        @Override
        public synchronized void complete() {
            completed = true;
            super.complete();
        }

        List<String> eventNames() {
            return sent.stream()
                    .filter(s -> s.startsWith("event:"))
                    .map(s -> s.substring("event:".length(), s.indexOf('\n')))
                    .toList();
        }

        int sentCount() {
            return sent.size();
        }
    }
}
