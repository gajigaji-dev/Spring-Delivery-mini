package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

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
    //  (ddl-auto: update는 기존 unique 제약을 지우지 않으므로 DB에서 직접 삭제 필요, README 6장 참고)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    // 결제 금액
    // 요청값이 아닌 주문 총액(Order.totalPrice)을 그대로 저장
    @Column(nullable = false)
    private int amount;

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

    public Payment(Order order, int amount, PaymentMethod paymentMethod) {
        this.order = order;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.paidAt = LocalDateTime.now();
    }
}
