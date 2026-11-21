package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.exception.InvalidPaymentStatusException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// BaseEntity 상속 → created_at(기록 생성 시각), updated_at(기록 수정 시각) 자동 기록
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자, 외부에서 new 금지
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 결제한 주문
    // 주문 1건당 결제는 1건만 가능 → order_id에 unique 제약
    // 동시에 같은 주문을 결제하는 요청이 들어와도 DB에서 중복 저장을 막음
    // 취소·환불은 전액만 지원 → 같은 결제 행의 상태만 바뀌므로 주문당 결제 1건(@OneToOne)을 유지 (V9)
    // TODO(@ManyToOne 전환): 결제 실패 후 재결제·부분 취소 등 한 주문에 결제 기록이 여러 건 필요해지면
    //  @ManyToOne으로 바꾸고 unique 제거
    //  (unique 제약은 새 Flyway 마이그레이션에서 DROP CONSTRAINT uk8vo36cen604as7etdfwmyjsxt로 삭제, docs/database.md 참고)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    // 결제 금액
    // 요청값이 아닌 주문 총액(Order.totalPrice)을 그대로 저장
    @Column(nullable = false)
    private long amount;

    // 플랫폼 수수료 (계산·반올림 정책은 PaymentFee 참고)
    // 결제 시점의 수수료율을 함께 저장 → 설정값이 나중에 바뀌어도 이 결제의 계산 근거가 남음
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;

    // 수수료 = 결제 금액 × 수수료율 (원 단위 HALF_UP 반올림)
    @Column(nullable = false)
    private long feeAmount;

    // 사장님 정산액 = 결제 금액 - 수수료 (수수료 + 정산액 = 결제 금액)
    @Column(nullable = false)
    private long settlementAmount;

    // 결제 수단 (현재는 CARD만 허용)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    // 결제 시각
    // createdAt과 지금은 거의 같은 값이지만 의미가 다름
    // createdAt: DB에 기록된 시각(기술적 기록) / paidAt: 결제가 승인된 시각(업무 데이터)
    // 이후 PG 연동 시 승인 시각을 PG 응답값으로 받으면 두 값이 달라질 수 있어 별도 필드로 유지
    @Column(nullable = false)
    private LocalDateTime paidAt;

    // 결제 상태 — COMPLETED로 시작, 취소(CANCELLED)·환불(REFUNDED)은 최종 상태 (PaymentStatus 참고)
    // 금액 필드는 취소·환불 후에도 바뀌지 않음 → 환불 차감액은 이 결제의 금액 그대로
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // 취소 시각 — CANCELLED일 때만 있음 (DB CHECK 제약, V9)
    private LocalDateTime cancelledAt;

    // 전액 환불이 끝난 시각 — REFUNDED일 때만 있음 (DB CHECK 제약, V9)
    // 정산 차감 기준 시각은 환불 1건마다의 PaymentRefund.refundedAt (V21부터, 부분 환불 포함)
    private LocalDateTime refundedAt;

    // 누적 환불액 (V21) — 원 결제 금액(amount)은 그대로 두고 환불 진행 상태만 기록
    // 0 = 환불 없음, 0 < x < amount = PARTIALLY_REFUNDED, amount = REFUNDED (DB CHECK 제약)
    // 환불 1건의 금액·수수료는 payment_refunds에 따로 남음 (정산 차감 단위)
    @Column(nullable = false)
    private long refundedAmount;

    // PG 승인 정보 (V20) — 토스페이먼츠를 거친 결제만 있음, 모의 결제(POST /api/payments)는 모두 null
    // 네 값은 함께 있거나 함께 없음 (DB CHECK 제약). 승인 후 바뀌지 않음 → updatable = false
    // pgPaymentKey: 토스 결제 키 — UNIQUE라 같은 키의 승인 요청이 다시 와도 결제가 두 번 저장되지 않음
    // pgApprovedAt: 토스 승인 시각. paidAt(서버 기록 시각, 정산 기준)과 따로 둠
    //   → 승인 결과를 늦게 확인해 기록한 경우(타임아웃 후 재시도) 정산 기준 시각이 이미 집계된 날로 들어가지 않음
    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private PgProvider pgProvider;

    @Column(length = 200, updatable = false)
    private String pgPaymentKey;

    @Column(length = 64, updatable = false)
    private String pgOrderId;

    @Column(updatable = false)
    private LocalDateTime pgApprovedAt;

    // paidAt은 호출하는 쪽(PaymentService)이 Clock 기준 현재 시각을 넘김
    // 엔티티 안에서 LocalDateTime.now()를 부르면 JVM 시간대에 의존하고 테스트에서 시각을 고정할 수 없음
    // 수수료·정산액은 밖에서 받지 않고 결제 금액과 수수료율로 여기서 계산 → 세 금액이 항상 서로 맞음
    public Payment(Order order, long amount, BigDecimal feeRate, PaymentMethod paymentMethod, LocalDateTime paidAt) {
        PaymentFee fee = PaymentFee.calculate(amount, feeRate);
        this.order = order;
        this.amount = amount;
        this.feeRate = fee.rate();
        this.feeAmount = fee.feeAmount();
        this.settlementAmount = fee.settlementAmount();
        this.paymentMethod = paymentMethod;
        this.paidAt = paidAt;
        this.status = PaymentStatus.COMPLETED;
    }

    // PG(토스페이먼츠) 승인 결과로 만드는 결제 — 금액·수수료 계산은 모의 결제와 같음
    // amount는 호출하는 쪽(PaymentService)이 주문 총액과 토스 승인 금액이 같은지 확인한 뒤 넘김
    public static Payment approvedByGateway(Order order, long amount, BigDecimal feeRate, PaymentMethod paymentMethod,
                                            LocalDateTime paidAt, PgProvider provider, String paymentKey,
                                            String pgOrderId, LocalDateTime approvedAt) {
        if (provider == null || paymentKey == null || paymentKey.isBlank()
                || pgOrderId == null || pgOrderId.isBlank() || approvedAt == null) {
            throw new IllegalArgumentException("PG 결제에는 공급자·결제 키·주문번호·승인 시각이 모두 필요합니다.");
        }
        Payment payment = new Payment(order, amount, feeRate, paymentMethod, paidAt);
        payment.pgProvider = provider;
        payment.pgPaymentKey = paymentKey;
        payment.pgOrderId = pgOrderId;
        payment.pgApprovedAt = approvedAt;
        return payment;
    }

    // PG를 거친 결제인지 — 취소·환불 때 PG 결제 취소 API도 호출해야 하는지 판단
    public boolean isGatewayPayment() {
        return pgProvider != null;
    }

    // 결제 취소 (배달 전) — COMPLETED에서만 가능
    // 주문 상태(주문수락 전인지)는 Order.cancel()이 판단, 이 메서드는 결제 상태만 확인
    // cancelledAt은 호출하는 쪽(OrderService)이 Clock 기준 현재 시각을 넘김
    public void cancel(LocalDateTime cancelledAt) {
        if (cancelledAt == null) {
            throw new IllegalArgumentException("취소 시각이 필요합니다.");
        }
        if (status != PaymentStatus.COMPLETED) {
            throw new InvalidPaymentStatusException(alreadyClosedCode(),
                    "완료된 결제만 취소할 수 있습니다. 현재 상태: " + status);
        }
        this.status = PaymentStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    // 남은 금액 전부 환불 (배달완료 후) — 기존 전액 환불 API(PATCH .../refund)
    // 부분 환불이 없던 결제면 결제 금액 전부 = 기존 전액 환불과 같은 금액·수수료
    public PaymentRefund refund(LocalDateTime refundedAt) {
        return refund(refundableAmount(), "전액 환불", refundedAt);
    }

    // 환불 1건 (배달완료 후, V21 부분 환불) — COMPLETED·PARTIALLY_REFUNDED이고 주문이 배달완료일 때만
    // 누적 환불액이 결제 금액에 닿으면 REFUNDED(최종, refundedAt 기록), 아니면 PARTIALLY_REFUNDED
    // 결제 금액·수수료·정산액(원 결제 기록)은 바꾸지 않음 → 환불분은 반환하는 PaymentRefund에 따로 남음
    // 호출하는 쪽(AdminPaymentService)이 결제 행을 잠근 뒤 부름 → 동시 환불이 남은 금액을 넘지 않음
    public PaymentRefund refund(long refundAmount, String reason, LocalDateTime refundedAt) {
        if (refundedAt == null) {
            throw new IllegalArgumentException("환불 시각이 필요합니다.");
        }
        if (status != PaymentStatus.COMPLETED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new InvalidPaymentStatusException(alreadyClosedCode(),
                    "완료된 결제만 환불할 수 있습니다. 현재 상태: " + status);
        }
        if (order.getStatus() != OrderStatus.DELIVERY_COMPLETED) {
            throw new InvalidPaymentStatusException(ErrorCode.PAYMENT_NOT_REFUNDABLE,
                    "배달완료된 주문의 결제만 환불할 수 있습니다. 배달 전 주문은 주문 취소를 이용하세요.");
        }
        if (refundedAt.isBefore(order.getDeliveredAt())) {
            throw new IllegalArgumentException("환불 시각은 배달완료 시각보다 앞설 수 없습니다.");
        }
        // 금액 범위(0 < 환불액 ≤ 남은 금액)는 PaymentFee.refundPortion이 확인 — 서비스가 먼저 400으로 거르고 여기는 최종 방어
        PaymentFee portion = PaymentFee.refundPortion(amount, feeRate, refundedAmount, refundAmount);
        this.refundedAmount += refundAmount;
        if (this.refundedAmount == amount) {
            this.status = PaymentStatus.REFUNDED;
            this.refundedAt = refundedAt;
        } else {
            this.status = PaymentStatus.PARTIALLY_REFUNDED;
        }
        return new PaymentRefund(this, refundAmount, portion.feeAmount(), portion.settlementAmount(), reason,
                refundedAt);
    }

    // 아직 환불하지 않은 금액
    public long refundableAmount() {
        return amount - refundedAmount;
    }

    // 이미 끝난 결제(취소·환불)에 다시 취소·환불을 요청한 경우의 구분 값 — 중복 요청으로 안내하기 위함
    private ErrorCode alreadyClosedCode() {
        return status == PaymentStatus.REFUNDED ? ErrorCode.PAYMENT_ALREADY_REFUNDED : ErrorCode.PAYMENT_ALREADY_CANCELLED;
    }
}
