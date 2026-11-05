package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

// 매장 소유권 판단 규칙 검증 (DB·Spring 없이 순수 단위 테스트)
class StoreTest {

    private static User user(Long id, String username, Role role) {
        User user = new User(username, "encoded-password", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private final User owner = user(1L, "owner1", Role.OWNER);
    private final Store store = new Store("치킨집", owner);

    @Test
    void 매장_주인이면_소유자다() {
        assertThat(store.isOwnedBy(owner)).isTrue();
    }

    @Test
    void id가_같으면_다른_객체여도_소유자다() {
        // 요청마다 DB에서 다시 조회한 User는 같은 회원이어도 다른 객체 → 객체 비교가 아닌 id 비교
        assertThat(store.isOwnedBy(user(1L, "owner1", Role.OWNER))).isTrue();
    }

    @Test
    void 다른_회원은_소유자가_아니다() {
        assertThat(store.isOwnedBy(user(2L, "owner2", Role.OWNER))).isFalse();
    }

    @Test
    void null이면_소유자가_아니다() {
        assertThat(store.isOwnedBy(null)).isFalse();
    }

    @Test
    void 주문의_매장은_메뉴의_매장으로_정해진다() {
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", store);
        Order order = new Order(menu, user(3L, "customer1", Role.CUSTOMER), 1, 18000, "서울시",
                OrderStatus.ORDER_REQUEST);

        assertThat(order.getStore()).isSameAs(store);
    }
}
