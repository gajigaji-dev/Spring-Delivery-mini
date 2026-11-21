package com.sparta.springdeliverymini.pg;

import com.sparta.springdeliverymini.config.TossPaymentsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 가짜 PG (toss.payments.mode=fake) — 토스 서버에 접속하지 않고 결제창 인증·승인·조회·취소를 메모리에서 흉내 (V21)
//
// 왜 두는지
// - 토스 테스트 키도 실제 돈은 오가지 않지만, 키 없이·인터넷 없이 전체 결제 흐름을 시연하고
//   실제 토스에서는 일부러 만들기 어려운 장애(승인됐는데 응답 유실, PG 지연)를 버튼 하나로 재현하기 위함
// - 서버 코드(TossPaymentService·PgCancelProcessor)는 실제 토스와 똑같이 동작 — 이 클래스만 바뀜
//
// 토스 동작 중 흉내 내는 것
// - 결제창 인증(authorize)한 금액·주문번호와 승인 요청이 다르면 거절
// - 같은 멱등 키의 승인 재요청은 처음 결과를 그대로 돌려줌, 키 없이 다시 승인하면 ALREADY_PROCESSED_PAYMENT
// - 부분 취소는 남은 금액(balanceAmount) 안에서만, 전액 취소된 결제는 ALREADY_CANCELED_PAYMENT
// 한계: 메모리 보관 → 서버를 재시작하면 기록이 사라짐(재시작 전 결제의 취소는 NOT_FOUND_PAYMENT로 재시도 후 FAILED), 인스턴스 1대 기준
// prod 프로필에서는 켤 수 없음 (config.ProdProfileGuard)
@Slf4j
@Component
@ConditionalOnProperty(name = "toss.payments.mode", havingValue = "fake")
public class FakeTossPaymentsClient implements TossPaymentsClient {

    public static final String PAYMENT_KEY_PREFIX = "fake_";

    // 결제창에서 고르는 결과 — 승인 요청 때 적용
    public enum Scenario {
        SUCCESS,          // 정상 승인
        CARD_REJECTED,    // 카드사 거절 (REJECT_CARD_COMPANY) — 승인되지 않음
        RESPONSE_LOST,    // 승인은 됐는데 응답이 유실됨(시간 초과) → 서버가 조회로 확인해 기록해야 함
        PG_DELAYED        // 첫 승인 요청은 처리되지 않고 시간 초과 → 503 PG_RESULT_UNKNOWN, 같은 요청 재시도 때 승인
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final Map<String, FakePayment> payments = new ConcurrentHashMap<>();
    private final Map<String, Object> confirmResults = new ConcurrentHashMap<>(); // 멱등 키 → TossPayment 또는 TossApiException

    public FakeTossPaymentsClient(Clock clock) {
        this.clock = clock;
        log.warn("토스페이먼츠 결제: 가짜 PG 모드 — 토스 서버에 접속하지 않음 (데모·테스트 전용)");
    }

    private static final class FakePayment {
        final String paymentKey;
        final String orderId;
        final long totalAmount;
        final Scenario scenario;
        long balanceAmount;
        String status = "IN_PROGRESS"; // 결제창 인증 완료, 승인 전
        String approvedAt;
        boolean delayedOnce;

        FakePayment(String paymentKey, String orderId, long totalAmount, Scenario scenario) {
            this.paymentKey = paymentKey;
            this.orderId = orderId;
            this.totalAmount = totalAmount;
            this.balanceAmount = totalAmount;
            this.scenario = scenario;
        }

        TossPayment snapshot() {
            return new TossPayment(paymentKey, orderId, status, totalAmount, balanceAmount, "카드", approvedAt);
        }
    }

    // 결제창 인증 — 실제로는 토스 결제창이 하는 일. 결제 키를 발급해 successUrl로 넘기게 함 (FakeTossCheckoutController)
    public String authorize(String orderId, long amount, Scenario scenario) {
        if (orderId == null || orderId.length() < 6 || orderId.length() > 64 || amount <= 0) {
            throw new IllegalArgumentException("주문번호(6~64자)와 양수 금액이 필요합니다.");
        }
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        String paymentKey = PAYMENT_KEY_PREFIX + HexFormat.of().formatHex(bytes);
        payments.put(paymentKey, new FakePayment(paymentKey, orderId, amount, scenario == null ? Scenario.SUCCESS : scenario));
        return paymentKey;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String clientKey() {
        return "fake_ck";
    }

    @Override
    public TossPaymentsProperties.Mode mode() {
        return TossPaymentsProperties.Mode.FAKE;
    }

    @Override
    public TossPayment confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        if (idempotencyKey != null) {
            Object previous = confirmResults.get(idempotencyKey);
            if (previous instanceof TossPayment payment) {
                return payment;
            }
            if (previous instanceof TossApiException error) {
                throw error;
            }
        }
        FakePayment payment = payments.get(paymentKey);
        if (payment == null) {
            throw new TossApiException(404, "NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다.");
        }
        synchronized (payment) {
            if (!payment.orderId.equals(orderId) || payment.totalAmount != amount) {
                throw new TossApiException(400, "INVALID_REQUEST", "결제창에서 인증한 주문번호·금액과 다릅니다.");
            }
            if ("DONE".equals(payment.status) || payment.status.contains("CANCELED")) {
                throw new TossApiException(400, TossApiException.ALREADY_PROCESSED_PAYMENT, "이미 처리된 결제 입니다.");
            }
            switch (payment.scenario) {
                case CARD_REJECTED -> {
                    payment.status = "ABORTED";
                    TossApiException error = new TossApiException(403, "REJECT_CARD_COMPANY", "결제 승인이 거절되었습니다.");
                    remember(idempotencyKey, error);
                    throw error;
                }
                case PG_DELAYED -> {
                    if (!payment.delayedOnce) {
                        payment.delayedOnce = true; // 이번 요청은 처리하지 않음 (상태 IN_PROGRESS 그대로)
                        throw new TossUnknownResultException("가짜 PG 응답 시간 초과 operation=confirm (처리 안 됨)");
                    }
                }
                default -> {
                }
            }
            approve(payment);
            TossPayment result = payment.snapshot();
            remember(idempotencyKey, result);
            if (payment.scenario == Scenario.RESPONSE_LOST) {
                // 승인은 끝났지만 응답이 도착하지 않은 상황 — 같은 멱등 키 재시도는 승인 결과를 받음
                throw new TossUnknownResultException("가짜 PG 응답 시간 초과 operation=confirm (승인은 됨)");
            }
            return result;
        }
    }

    @Override
    public TossPayment getPayment(String paymentKey) {
        FakePayment payment = payments.get(paymentKey);
        if (payment == null) {
            throw new TossApiException(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.");
        }
        synchronized (payment) {
            return payment.snapshot();
        }
    }

    @Override
    public TossPayment cancel(String paymentKey, String cancelReason, Long cancelAmount, String idempotencyKey) {
        FakePayment payment = payments.get(paymentKey);
        if (payment == null) {
            throw new TossApiException(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.");
        }
        synchronized (payment) {
            if ("CANCELED".equals(payment.status)) {
                throw new TossApiException(400, TossApiException.ALREADY_CANCELED_PAYMENT, "이미 취소된 결제 입니다.");
            }
            if (!"DONE".equals(payment.status) && !"PARTIAL_CANCELED".equals(payment.status)) {
                throw new TossApiException(403, "NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다.");
            }
            long amount = cancelAmount == null ? payment.balanceAmount : cancelAmount;
            if (amount <= 0 || amount > payment.balanceAmount) {
                throw new TossApiException(403, "NOT_CANCELABLE_AMOUNT", "취소 할 수 없는 금액 입니다.");
            }
            payment.balanceAmount -= amount;
            payment.status = payment.balanceAmount == 0 ? "CANCELED" : "PARTIAL_CANCELED";
            log.info("가짜 PG 취소 amount={}, balance={}", amount, payment.balanceAmount);
            return payment.snapshot();
        }
    }

    private void approve(FakePayment payment) {
        payment.status = "DONE";
        payment.approvedAt = OffsetDateTime.now(clock).toString();
    }

    private void remember(String idempotencyKey, Object result) {
        if (idempotencyKey != null) {
            confirmResults.put(idempotencyKey, result);
        }
    }
}
