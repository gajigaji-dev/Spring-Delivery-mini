package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

// 주문 항목 — 주문 1건 : 항목 N개, 항목 1개 = 메뉴 1종 × 수량
// 주문 당시의 메뉴 이름·단가를 그대로 복사해 저장(스냅샷)
//   → 이후 메뉴 이름·가격이 바뀌거나 메뉴가 삭제되어도 주문 내역은 주문 시점 그대로 유지
// 생성·수량 변경은 Order.addItem()을 통해서만 → 총액 계산과 같은 매장 검증을 Order가 한곳에서 처리
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자, 외부에서 new 금지
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 항목이 속한 주문 (연관관계의 주인, order_items.order_id)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // 주문한 메뉴 — 어떤 메뉴였는지 추적용 (표시는 아래 스냅샷 값을 사용)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    // 주문 당시 메뉴 이름 (스냅샷)
    @Column(nullable = false, length = 50)
    private String menuName;

    // 주문 당시 메뉴 단가 (스냅샷)
    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    // Order.addItem()에서만 생성
    OrderItem(Order order, Menu menu, int quantity) {
        this.order = order;
        this.menu = menu;
        this.menuName = menu.getName();
        this.unitPrice = menu.getPrice();
        this.quantity = quantity;
    }

    // 항목 금액 = 단가 × 수량
    // long 범위를 넘으면 ArithmeticException (잘못된 금액이 저장되지 않도록, 응답 코드는 Service가 결정)
    public long getLinePrice() {
        return Math.multiplyExact(unitPrice, (long) quantity);
    }
}
