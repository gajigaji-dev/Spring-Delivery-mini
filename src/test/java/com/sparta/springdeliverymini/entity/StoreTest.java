package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 매장 소유권 판단 규칙, 매장별 수수료율(공통 수수료율 fallback) 검증 (DB·Spring 없이 순수 단위 테스트)
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
        Order order = new Order(user(3L, "customer1", Role.CUSTOMER), store, "서울시", OrderStatus.ORDER_REQUEST);
        order.addItem(menu, 1);

        assertThat(order.getStore()).isSameAs(store);
    }

    // ===== 매장별 수수료율 =====

    private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.05");

    @Test
    void 매장_수수료율이_없으면_공통_수수료율을_적용한다() {
        assertThat(store.getFeeRate()).isNull();
        assertThat(store.effectiveFeeRate(DEFAULT_RATE)).isEqualByComparingTo("0.05");
    }

    @Test
    void 매장_수수료율이_있으면_매장_수수료율을_적용한다() {
        store.changeFeeRate(new BigDecimal("0.03"));

        assertThat(store.getFeeRate()).isEqualTo(new BigDecimal("0.0300")); // DB와 같은 4자리
        assertThat(store.effectiveFeeRate(DEFAULT_RATE)).isEqualByComparingTo("0.03");
    }

    @Test
    void 수수료율_0은_공통_수수료율로_대체되지_않는다() {
        store.changeFeeRate(BigDecimal.ZERO);

        assertThat(store.effectiveFeeRate(DEFAULT_RATE)).isEqualByComparingTo("0");
    }

    @Test
    void 수수료율_경계값_0과_1은_설정할_수_있다() {
        store.changeFeeRate(new BigDecimal("1"));
        assertThat(store.getFeeRate()).isEqualByComparingTo("1");

        store.changeFeeRate(new BigDecimal("0.0001"));
        assertThat(store.getFeeRate()).isEqualByComparingTo("0.0001");
    }

    @Test
    void 매장_수수료율을_해제하면_다시_공통_수수료율을_적용한다() {
        store.changeFeeRate(new BigDecimal("0.03"));

        store.clearFeeRate();

        assertThat(store.getFeeRate()).isNull();
        assertThat(store.effectiveFeeRate(DEFAULT_RATE)).isEqualByComparingTo("0.05");
    }

    @Test
    void 범위를_벗어나거나_소수점_4자리를_넘는_수수료율은_거부하고_기존_값을_유지한다() {
        store.changeFeeRate(new BigDecimal("0.03"));

        for (String invalid : new String[]{"-0.0001", "1.0001", "0.00001"}) {
            assertThatThrownBy(() -> store.changeFeeRate(new BigDecimal(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.changeFeeRate(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.getFeeRate()).isEqualByComparingTo("0.03");
    }
}
