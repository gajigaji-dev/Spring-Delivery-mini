package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
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

    // 환불 시각 — REFUNDED일 때만 있음 (DB CHECK 제약, V9)
    // 정산에서 차감되는 시점의 기준: 이 시각 이후에 집계되는 정산 중 처음 것에서 차감 (docs/settlement-policy.md 9장)
    private LocalDateTime refundedAt;

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

    // 결제 취소 (배달 전) — COMPLETED에서만 가능
    // 주문 상태(주문수락 전인지)는 Order.cancel()이 판단, 이 메서드는 결제 상태만 확인
    // cancelledAt은 호출하는 쪽(OrderService)이 Clock 기준 현재 시각을 넘김
    public void cancel(LocalDateTime cancelledAt) {
        if (cancelledAt == null) {
            throw new IllegalArgumentException("취소 시각이 필요합니다.");
        }
        if (status != PaymentStatus.COMPLETED) {
            throw new InvalidPaymentStatusException("완료된 결제만 취소할 수 있습니다. 현재 상태: " + status);
        }
        this.status = PaymentStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    // 전액 환불 (배달완료 후) — COMPLETED이고 주문이 배달완료일 때만 가능
    // 이미 환불된 결제는 다시 환불하지 않음 (환불 시각도 처음 값 유지)
    // refundedAt은 호출하는 쪽(AdminPaymentService)이 Clock 기준 현재 시각을 넘김
    public void refund(LocalDateTime refundedAt) {
        if (refundedAt == null) {
            throw new IllegalArgumentException("환불 시각이 필요합니다.");
        }
        if (status != PaymentStatus.COMPLETED) {
            throw new InvalidPaymentStatusException("완료된 결제만 환불할 수 있습니다. 현재 상태: " + status);
        }
        if (order.getStatus() != OrderStatus.DELIVERY_COMPLETED) {
            throw new InvalidPaymentStatusException("배달완료된 주문의 결제만 환불할 수 있습니다. 배달 전 주문은 주문 취소를 이용하세요.");
        }
        if (refundedAt.isBefore(order.getDeliveredAt())) {
            throw new IllegalArgumentException("환불 시각은 배달완료 시각보다 앞설 수 없습니다.");
        }
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = refundedAt;
    }
}
