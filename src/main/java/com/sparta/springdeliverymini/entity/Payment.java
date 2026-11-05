package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
    // TODO(@ManyToOne 전환): 결제 실패 후 재결제·부분 취소 등 한 주문에 결제 기록이 여러 건 필요해지면
    //  @ManyToOne으로 바꾸고 unique 제거 + 결제 상태(SUCCESS/FAILED/CANCELLED) 필드 추가
    //  (unique 제약은 새 Flyway 마이그레이션에서 DROP CONSTRAINT uk8vo36cen604as7etdfwmyjsxt로 삭제, README 6장 참고)
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
    }
}
