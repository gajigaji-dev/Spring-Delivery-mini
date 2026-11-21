package com.sparta.springdeliverymini.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// PG 결제 취소 요청 (V20) — 토스 결제 취소 API를 호출해야 하는 결제 1건
//
// 왜 바로 호출하지 않고 행을 남기는지
// - 주문 취소·환불은 DB에서 먼저 확정해야 함(주문 행 잠금 안에서 상태 판단). 그 잠금을 쥔 채 외부 API를 기다리면
//   토스 지연 동안 커넥션·잠금을 잡고, 커밋 전에 토스 취소가 성공한 뒤 커밋이 실패하면 "환불됐는데 주문은 살아 있는" 상태가 됨
// - 그래서 같은 트랜잭션에 이 행을 남기고(Outbox와 같은 방식) 커밋 후 호출 → 실패·시간 초과는 백오프로 재시도
//
// 중복 환불이 나지 않는 근거
// - balance_after: 이 취소가 반영되면 토스에 남아야 할 잔액. 결과를 모르는 채 끝난 시도 뒤에는 토스 잔액을 먼저 조회해
//   balance_after 이하면 이미 반영된 것으로 보고 DONE (부분 취소는 두 번 보내면 두 번 환불되므로 이 확인이 필수)
// - 같은 결제의 요청은 먼저 생긴 것부터 하나씩 (앞 요청이 DONE이 아니면 뒤 요청은 기다림) → 잔액 비교가 정확함
// - 중복 요청 방지: 환불 1건당 1행(payment_refund_id UNIQUE), 환불 없는 전액 취소(주문 취소·보상)는 결제 키당 1행(부분 유니크)
// - 처리할 행은 잠금 + 임대 시각(next_attempt_at)으로 한 작업자만 가져감 (PgCancelRequestRepository)
@Entity
@Table(name = "pg_cancel_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgCancelRequest extends BaseEntity {

    // 토스 cancelReason 최대 200자, last_error 컬럼 500자
    private static final int MAX_REASON_LENGTH = 200;
    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 취소할 결제 — 승인 후 결제 행을 만들지 못한 보상 취소에는 없음(null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", updatable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PgProvider pgProvider;

    @Column(nullable = false, length = 200, updatable = false)
    private String paymentKey;

    // 관리자 환불(부분·전액)로 생긴 취소면 그 환불 1건 — 주문 취소·보상 취소는 null
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_refund_id", updatable = false)
    private PaymentRefund paymentRefund;

    // 이번 취소 금액 — 토스에 cancelAmount로 보냄 (남은 금액 전부면 전액 취소와 같음)
    @Column(nullable = false, updatable = false)
    private long amount;

    // 이 취소가 반영된 뒤 토스 결제에 남아야 할 잔액 (전액 취소면 0)
    @Column(nullable = false, updatable = false)
    private long balanceAfter;

    @Column(nullable = false, length = 200, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgCancelStatus status;

    // 결과를 받은(또는 결과를 모르는 채 끝난) 시도 횟수
    @Column(nullable = false)
    private int attempts;

    // PENDING: 다음 시도 가능 시각 / 처리 중: 임대 만료 시각 (작업자가 죽으면 이 시각 뒤 다른 작업자가 가져감)
    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    // 마지막 실패 원인 — 토스 오류 코드·예외 종류만 (시크릿 키·요청 본문은 넣지 않음)
    @Column(length = 500)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime completedAt;

    private PgCancelRequest(Payment payment, PaymentRefund paymentRefund, PgProvider pgProvider, String paymentKey,
                            long amount, long balanceAfter, String reason, LocalDateTime requestedAt) {
        if (pgProvider == null || paymentKey == null || paymentKey.isBlank() || amount <= 0 || balanceAfter < 0
                || requestedAt == null) {
            throw new IllegalArgumentException("PG 취소 요청에는 공급자·결제 키·양수 금액·0 이상 잔액·요청 시각이 필요합니다.");
        }
        this.payment = payment;
        this.paymentRefund = paymentRefund;
        this.balanceAfter = balanceAfter;
        this.pgProvider = pgProvider;
        this.paymentKey = paymentKey;
        this.amount = amount;
        this.reason = abbreviate(reason == null || reason.isBlank() ? "결제 취소" : reason, MAX_REASON_LENGTH);
        this.status = PgCancelStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = requestedAt;
        this.requestedAt = requestedAt;
    }

    // 주문 취소(배달 전)로 CANCELLED가 된 PG 결제 — 전액
    public static PgCancelRequest forPayment(Payment payment, String reason, LocalDateTime requestedAt) {
        requireGateway(payment);
        return new PgCancelRequest(payment, null, payment.getPgProvider(), payment.getPgPaymentKey(),
                payment.getAmount(), 0, reason, requestedAt);
    }

    // 관리자 환불 1건(V21, 부분·전액) — 환불 후 결제의 남은 금액이 토스 잔액이 되어야 함
    public static PgCancelRequest forRefund(PaymentRefund refund, LocalDateTime requestedAt) {
        Payment payment = refund.getPayment();
        requireGateway(payment);
        return new PgCancelRequest(payment, refund, payment.getPgProvider(), payment.getPgPaymentKey(),
                refund.getAmount(), payment.refundableAmount(), refund.getReason(), requestedAt);
    }

    // 승인은 됐지만 결제 행을 만들지 못한 경우의 보상 취소 (주문이 그 사이 취소됨·다른 결제로 이미 결제됨 등) — 전액
    public static PgCancelRequest compensation(PgProvider provider, String paymentKey, long amount, String reason,
                                               LocalDateTime requestedAt) {
        return new PgCancelRequest(null, null, provider, paymentKey, amount, 0, reason, requestedAt);
    }

    private static void requireGateway(Payment payment) {
        if (payment == null || !payment.isGatewayPayment()) {
            throw new IllegalArgumentException("PG를 거친 결제만 PG 취소 요청을 만들 수 있습니다.");
        }
    }

    // 작업자가 이 행을 가져감 — 임대 만료 전에는 다른 작업자가 가져가지 않음
    public void claim(LocalDateTime leaseUntil) {
        requirePending();
        this.nextAttemptAt = leaseUntil;
    }

    // 토스에서 취소 완료(또는 이미 취소됨)를 확인
    public void markDone(LocalDateTime completedAt) {
        requirePending();
        this.attempts++;
        this.status = PgCancelStatus.DONE;
        this.completedAt = completedAt;
        this.lastError = null;
    }

    // 실패·결과 불명 — max-attempts에 닿으면 FAILED, 아니면 nextAttemptAt에 다시 시도
    // 결과를 모르는 실패도 재시도해도 안전: 다음 시도는 토스 잔액부터 확인해 이미 반영됐으면 다시 보내지 않음 (PgCancelProcessor)
    public void markFailedAttempt(String error, LocalDateTime nextAttemptAt, int maxAttempts) {
        requirePending();
        this.attempts++;
        this.lastError = abbreviate(error, MAX_ERROR_LENGTH);
        if (this.attempts >= maxAttempts) {
            this.status = PgCancelStatus.FAILED;
        } else {
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private void requirePending() {
        if (status != PgCancelStatus.PENDING) {
            throw new IllegalStateException("처리 대기 중인 PG 취소 요청이 아닙니다: id=" + id + ", status=" + status);
        }
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
