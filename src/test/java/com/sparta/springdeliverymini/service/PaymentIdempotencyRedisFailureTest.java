package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.RedisTestSupport;
import com.sparta.springdeliverymini.config.PaymentIdempotencyProperties;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.PaymentMethod;
import com.sparta.springdeliverymini.entity.PaymentStatus;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService.PaymentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Redis 장애·타임아웃에서 결제 멱등성 처리가 안전하게 실패하는지 검증 (#41)
// - 결제 서비스는 Mock (DB 없이), Redis는 실제 클라이언트로 연결 실패·명령 타임아웃을 재현
// - 정책: 결제 전 Redis를 쓸 수 없으면 결제하지 않고 503 / 결제 후 Redis 실패는 결제 결과를 그대로 응답
@ExtendWith(MockitoExtension.class)
class PaymentIdempotencyRedisFailureTest {

    private static final PaymentIdempotencyProperties PROPERTIES =
            new PaymentIdempotencyProperties(Duration.ofMinutes(1), Duration.ofHours(24));
    private static final PaymentRequest REQUEST = new PaymentRequest(1L, PaymentMethod.CARD.name());
    private static final PaymentResponse RESPONSE = new PaymentResponse(10L, 1L, 36000, PaymentMethod.CARD,
            OrderStatus.PAYMENT_COMPLETED, LocalDateTime.of(2026, 11, 14, 12, 0), PaymentStatus.COMPLETED, null, null);

    @Mock
    private PaymentService paymentService;

    private PaymentIdempotencyService service(StringRedisTemplate redis) {
        return new PaymentIdempotencyService(paymentService, redis, JsonMapper.builder().build(), PROPERTIES);
    }

    // 운영 설정처럼 짧은 명령 타임아웃 (테스트 시간을 줄이려 1초 대신 300ms)
    private PaymentIdempotencyService serviceWithRunningRedis() {
        return service(RedisTestSupport.template(Duration.ofMillis(300)));
    }

    @AfterEach
    void tearDown() {
        RedisTestSupport.resetAfterPause();
    }

    @Test
    void Redis에_연결할_수_없으면_결제하지_않고_503() {
        PaymentIdempotencyService service = service(RedisTestSupport.unreachableTemplate());

        assertThatThrownBy(() -> service.pay(REQUEST, "customer1", "key-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(paymentService);
    }

    @Test
    void Redis_응답이_타임아웃되면_결제하지_않고_503() {
        PaymentIdempotencyService service = serviceWithRunningRedis();
        RedisTestSupport.pause(Duration.ofSeconds(1));

        assertThatThrownBy(() -> service.pay(REQUEST, "customer1", "key-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(paymentService);
    }

    @Test
    void Redis가_장애여도_키_없는_결제는_기존대로_처리된다() {
        given(paymentService.pay(REQUEST, "customer1")).willReturn(RESPONSE);
        PaymentIdempotencyService service = service(RedisTestSupport.unreachableTemplate());

        PaymentResult result = service.pay(REQUEST, "customer1", null);

        assertThat(result.response()).isEqualTo(RESPONSE);
        assertThat(result.replayed()).isFalse();
    }

    @Test
    void 결제_후_완료_기록이_타임아웃돼도_결제_응답은_그대로_돌려준다() {
        // 결제(DB 커밋)는 끝났는데 그 직후 Redis가 멈춤 → 완료 기록 실패
        given(paymentService.pay(REQUEST, "customer1")).willAnswer(inv -> {
            RedisTestSupport.pause(Duration.ofSeconds(1));
            return RESPONSE;
        });

        PaymentResult result = serviceWithRunningRedis().pay(REQUEST, "customer1", "key-1");

        assertThat(result.response()).isEqualTo(RESPONSE);
        assertThat(result.replayed()).isFalse();
    }

    @Test
    void 결제가_실패한_뒤_기록_삭제가_타임아웃돼도_원래_결제_오류를_그대로_던진다() {
        given(paymentService.pay(any(), anyString())).willAnswer(inv -> {
            RedisTestSupport.pause(Duration.ofSeconds(1));
            throw new ApiException(HttpStatus.NOT_FOUND, "주문이 없습니다.");
        });

        assertThatThrownBy(() -> serviceWithRunningRedis().pay(REQUEST, "customer1", "key-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND)); // 503으로 바뀌지 않음
        verify(paymentService).pay(REQUEST, "customer1");
    }

    @Test
    void 결제_후_완료_기록이_타임아웃된_키로_재시도해도_결제를_다시_실행하지_않는다() {
        given(paymentService.pay(REQUEST, "customer1")).willAnswer(inv -> {
            RedisTestSupport.pause(Duration.ofSeconds(1));
            return RESPONSE;
        });
        PaymentIdempotencyService service = serviceWithRunningRedis();
        service.pay(REQUEST, "customer1", "key-1");
        awaitRedis();

        // 타임아웃된 완료 기록은 Redis가 회복된 뒤 늦게 반영될 수 있고(→ 재전송), 끝내 반영되지 않으면
        // 처리 중 기록이 남아 409 → 어느 쪽이든 결제는 다시 실행되지 않음
        try {
            PaymentResult retry = service.pay(REQUEST, "customer1", "key-1");
            assertThat(retry.replayed()).isTrue();
            assertThat(retry.response()).isEqualTo(RESPONSE);
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        }
        verify(paymentService).pay(REQUEST, "customer1"); // 한 번만
    }

    // 멈춤이 풀릴 때까지 대기 (키는 지우지 않음)
    private static void awaitRedis() {
        RedisTestSupport.template(Duration.ofSeconds(30)).execute(connection -> connection.ping(), true);
    }
}
