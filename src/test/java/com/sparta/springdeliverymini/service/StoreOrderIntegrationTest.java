package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

// 매장 → 메뉴 → 주문 흐름을 실제 PostgreSQL로 검증
// (Mock 테스트로는 확인할 수 없는 매핑·FK·Query Method(findAllByStoreOwnerId 등)의 실제 동작 확인)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StoreOrderIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;

    private StoreResponse chickenStore;
    private StoreResponse pizzaStore;
    private StoreResponse otherOwnerStore;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("owner2", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));

        // owner1은 매장 2개, owner2는 매장 1개
        chickenStore = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        pizzaStore = storeService.createStore(new StoreCreateRequest("피자집"), "owner1");
        otherOwnerStore = storeService.createStore(new StoreCreateRequest("분식집"), "owner2");
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: orders(order_items는 ON DELETE CASCADE로 함께 삭제) → menus → stores → users
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private OrderResponse order(StoreResponse store, String menuName, String ownerName) {
        MenuResponse menu = menuService.createMenu(
                new MenuCreateRequest(store.id(), menuName, 10000L, null), ownerName);
        return orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
    }

    @Test
    void 여러_메뉴를_담은_주문은_항목별로_저장되고_메뉴가_바뀌어도_주문_당시_값이_유지된다() {
        MenuResponse chicken = menuService.createMenu(
                new MenuCreateRequest(chickenStore.id(), "후라이드 치킨", 18000L, null), "owner1");
        MenuResponse coke = menuService.createMenu(
                new MenuCreateRequest(chickenStore.id(), "콜라", 2000L, null), "owner1");

        OrderResponse created = orderService.createOrder(new OrderCreateRequest(List.of(
                new OrderItemRequest(chicken.id(), 2),
                new OrderItemRequest(coke.id(), 3)), "서울시"), "customer1");

        // 주문 후 메뉴 이름·가격 변경, 메뉴 삭제
        menuService.putMenu(chicken.id(), new MenuUpdateRequest("크리스피 치킨", 20000L, null), "owner1");
        menuService.deleteMenu(coke.id(), "owner1");

        // DB에서 다시 읽어도 주문 당시 이름·단가와 총액 그대로
        OrderResponse reloaded = orderService.getOrders("customer1").get(0);
        assertThat(reloaded.id()).isEqualTo(created.id());
        assertThat(reloaded.totalPrice()).isEqualTo(18000L * 2 + 2000L * 3);
        assertThat(reloaded.items())
                .extracting(OrderItemResponse::menuName, OrderItemResponse::unitPrice, OrderItemResponse::quantity)
                .containsExactlyInAnyOrder(
                        tuple("후라이드 치킨", 18000L, 2),
                        tuple("콜라", 2000L, 3));
    }

    @Test
    void 다른_매장의_메뉴를_함께_담으면_400이고_주문은_저장되지_않는다() {
        MenuResponse chicken = menuService.createMenu(
                new MenuCreateRequest(chickenStore.id(), "후라이드 치킨", 18000L, null), "owner1");
        MenuResponse pizza = menuService.createMenu(
                new MenuCreateRequest(pizzaStore.id(), "치즈 피자", 20000L, null), "owner1");

        assertThatThrownBy(() -> orderService.createOrder(new OrderCreateRequest(List.of(
                new OrderItemRequest(chicken.id(), 1),
                new OrderItemRequest(pizza.id(), 1)), "서울시"), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void 주문은_메뉴의_매장으로_저장된다() {
        OrderResponse order = order(chickenStore, "후라이드 치킨", "owner1");

        assertThat(order.storeId()).isEqualTo(chickenStore.id());
        assertThat(order.storeName()).isEqualTo("치킨집");
    }

    @Test
    void 매장별_주문은_해당_매장의_주문만_조회된다() {
        OrderResponse chickenOrder = order(chickenStore, "후라이드 치킨", "owner1");
        order(pizzaStore, "치즈 피자", "owner1");
        order(otherOwnerStore, "떡볶이", "owner2");

        List<OrderResponse> orders = orderService.getStoreOrders(chickenStore.id(), "owner1");

        assertThat(orders).extracting(OrderResponse::id).containsExactly(chickenOrder.id());
    }

    @Test
    void OWNER_전체_주문_조회는_본인의_모든_매장_주문만_포함한다() {
        order(chickenStore, "후라이드 치킨", "owner1");
        order(pizzaStore, "치즈 피자", "owner1");
        order(otherOwnerStore, "떡볶이", "owner2");

        List<OrderResponse> orders = orderService.getOrders("owner1");

        assertThat(orders).extracting(OrderResponse::storeId)
                .containsExactlyInAnyOrder(chickenStore.id(), pizzaStore.id());
    }

    @Test
    void 다른_OWNER의_매장_주문은_조회할_수_없다() {
        order(chickenStore, "후라이드 치킨", "owner1");

        assertThatThrownBy(() -> orderService.getStoreOrders(chickenStore.id(), "owner2"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 다른_OWNER의_매장에는_메뉴를_등록할_수_없다() {
        assertThatThrownBy(() -> menuService.createMenu(
                new MenuCreateRequest(chickenStore.id(), "몰래 등록", 10000L, null), "owner2"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }
}
