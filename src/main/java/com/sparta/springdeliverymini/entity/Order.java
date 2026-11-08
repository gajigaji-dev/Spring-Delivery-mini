package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// BaseEntity 상속 → created_at(주문 시각), updated_at(상태 변경 시각) 자동 기록
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자, 외부에서 new 금지
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 주문한 사용자
    // 주문 목록 조회 시 본인의 주문인지 확인하기 위해 사용자 정보 저장
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 주문이 들어간 매장
    // 주문 항목의 메뉴는 모두 이 매장의 메뉴여야 함 (addItem에서 검증)
    // 매장별 주문 조회·소유권 검증을 항목·메뉴를 거치지 않고 처리하기 위해 직접 저장
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    // 주문 항목 (메뉴 1종 × 수량)
    // 항목은 주문 없이 존재할 수 없음 → 주문 저장·삭제 시 함께 저장·삭제(cascade), 목록에서 빠지면 삭제(orphanRemoval)
    // 외부에는 읽기 전용으로만 노출 → 항목 추가는 addItem()으로만
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    // 주문 당시의 총 금액
    // 항목 금액(단가 × 수량)의 합, addItem()에서만 갱신
    @Column(nullable = false)
    private long totalPrice;

    // 배송 주소
    @Column(nullable = false)
    private String deliveryAddress;

    // 주문 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    // 배달완료 시각 — 정산 기간을 나누는 기준 (docs/settlement-policy.md)
    // complete()에서 한 번만 기록, 배달완료 전에는 null
    // updated_at과 따로 두는 이유: updated_at은 이후 다른 값이 바뀌면 움직여 정산 기간이 달라질 수 있음
    // DB에서도 "배달완료 상태 ⇔ delivered_at 있음"을 CHECK 제약으로 강제 (V6)
    private LocalDateTime deliveredAt;

    // 항목 없이 생성 → addItem()으로 항목을 담음 (총액은 0원에서 시작)
    public Order(
            User user,
            Store store,
            String deliveryAddress,
            OrderStatus status
    ) {
        this.user = user;
        this.store = store;
        this.deliveryAddress = deliveryAddress;
        this.status = status;
    }

    // 주문 항목 추가 + 총액 갱신
    // - 다른 매장의 메뉴, 이미 담긴 메뉴, 1개 미만 수량 → IllegalArgumentException
    //   (Service가 먼저 검증해 400으로 응답하므로 여기까지 오면 프로그래밍 오류)
    // - 총액이 long 범위를 넘으면 ArithmeticException, 이때 항목·총액은 바뀌지 않음
    public void addItem(Menu menu, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("수량은 1개 이상이어야 합니다.");
        }
        if (!isSameEntity(menu.getStore(), store)) {
            throw new IllegalArgumentException("주문한 매장의 메뉴만 담을 수 있습니다.");
        }
        if (orderItems.stream().anyMatch(item -> isSameEntity(item.getMenu(), menu))) {
            throw new IllegalArgumentException("이미 담긴 메뉴입니다.");
        }

        OrderItem item = new OrderItem(this, menu, quantity);
        long newTotal = Math.addExact(totalPrice, item.getLinePrice()); // 넘치면 여기서 예외 → 아래 반영 안 됨
        orderItems.add(item);
        totalPrice = newTotal;
    }

    public List<OrderItem> getOrderItems() {
        return Collections.unmodifiableList(orderItems);
    }

    // 같은 엔티티인지 — 저장된 엔티티는 id로, 저장 전(id 없음)이면 같은 객체인지로 판단
    // (지연 로딩 프록시도 getId()는 초기화 없이 동작)
    private static boolean isSameEntity(Object a, Object b) {
        if (a == b) {
            return true;
        }
        Long idA = idOf(a);
        return idA != null && idA.equals(idOf(b));
    }

    private static Long idOf(Object entity) {
        return switch (entity) {
            case Store s -> s.getId();
            case Menu m -> m.getId();
            case null, default -> null;
        };
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
    // deliveredAt은 호출하는 쪽(OrderService)이 Clock 기준 현재 시각을 넘김 (Payment.paidAt과 같은 방식)
    public void complete(LocalDateTime deliveredAt) {
        validateNotDelivered();
        if (this.status != OrderStatus.ORDER_ACCEPTED) {
            throw new InvalidOrderStatusException("허용되지 않는 주문 상태 변경입니다.");
        }
        if (deliveredAt == null) {
            throw new IllegalArgumentException("배달완료 시각이 필요합니다.");
        }
        this.status = OrderStatus.DELIVERY_COMPLETED;
        this.deliveredAt = deliveredAt;
    }

    // 배달완료는 최종 상태 → 이후 어떤 변경도 불가
    private void validateNotDelivered() {
        if (this.status == OrderStatus.DELIVERY_COMPLETED) {
            throw new InvalidOrderStatusException("이미 배달완료된 주문은 상태를 변경할 수 없습니다.");
        }
    }
}
