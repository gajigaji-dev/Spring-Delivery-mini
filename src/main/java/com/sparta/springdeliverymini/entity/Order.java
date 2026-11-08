package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import jakarta.persistence.*;

// BaseEntity 상속 → created_at(주문 시각), updated_at(상태 변경 시각) 자동 기록
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자, 외부에서 new 금지
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 주문한 메뉴
    // Menu가 삭제되어도 Order 기록은 남아있어야 하므로 실제 메뉴 Entity를 참조
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    // 주문한 사용자
    // 주문 목록 조회 시 본인의 주문인지 확인하기 위해 사용자 정보 저장
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 주문 수량
    @Column(nullable = false)
    private int quantity;

    // 주문 당시의 총 금액
    // 메뉴 가격 × 주문 수량으로 서버에서 계산
    @Column(nullable = false)
    private int totalPrice;

    // 배송 주소
    @Column(nullable = false)
    private String deliveryAddress;

    // 주문 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    public Order(
            Menu menu,
            User user,
            int quantity,
            int totalPrice,
            String deliveryAddress,
            OrderStatus status
    ) {
        this.menu = menu;
        this.user = user;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.deliveryAddress = deliveryAddress;
        this.status = status;
    }

    // 상태 전이 규칙은 아래 메서드에만 둠
    // 어디서 호출하든 현재 상태를 확인하므로, 결제 전 주문이 배달완료되는 등의 잘못된 전이가 불가능
    // (권한·본인 확인은 Service 책임)

    // 주문 취소: ORDER_REQUEST → ORDER_CANCELLED
    public void cancel() {
        if (this.status != OrderStatus.ORDER_REQUEST) {
            throw new InvalidOrderStatusException("주문요청 상태에서만 주문을 취소할 수 있습니다.");
        }
        this.status = OrderStatus.ORDER_CANCELLED;
    }

    // 결제 완료: ORDER_REQUEST → PAYMENT_COMPLETED
    // 이미 결제됐거나(중복 결제) 취소된 주문은 불가
    public void pay() {
        if (this.status != OrderStatus.ORDER_REQUEST) {
            throw new InvalidOrderStatusException("주문요청 상태의 주문만 결제할 수 있습니다.");
        }
        this.status = OrderStatus.PAYMENT_COMPLETED;
    }

    // 주문 수락: PAYMENT_COMPLETED → ORDER_ACCEPTED
    public void accept() {
        validateNotDelivered();
        if (this.status != OrderStatus.PAYMENT_COMPLETED) {
            throw new InvalidOrderStatusException("허용되지 않는 주문 상태 변경입니다.");
        }
        this.status = OrderStatus.ORDER_ACCEPTED;
    }

    // 배달 완료: ORDER_ACCEPTED → DELIVERY_COMPLETED
    // 단계 건너뛰기(결제완료 → 배달완료) 불가
    public void complete() {
        validateNotDelivered();
        if (this.status != OrderStatus.ORDER_ACCEPTED) {
            throw new InvalidOrderStatusException("허용되지 않는 주문 상태 변경입니다.");
        }
        this.status = OrderStatus.DELIVERY_COMPLETED;
    }

    // 배달완료는 최종 상태 → 이후 어떤 변경도 불가
    private void validateNotDelivered() {
        if (this.status == OrderStatus.DELIVERY_COMPLETED) {
            throw new InvalidOrderStatusException("이미 배달완료된 주문은 상태를 변경할 수 없습니다.");
        }
    }
}
