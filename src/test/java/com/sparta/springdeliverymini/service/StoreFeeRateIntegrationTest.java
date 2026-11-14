package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 매장별 수수료율을 실제 PostgreSQL로 검증 (docs/money-policy.md)
// - 결제 시점의 매장 수수료율(없으면 공통 5%)이 결제에 스냅샷으로 저장됨
// - 수수료율을 바꿔도 이미 저장된 결제·정산(확정·지급 포함)은 바뀌지 않음
// - 결제 후 배달완료 전에 바뀌어도, 환불 차감도 결제 시점 수수료율 기준
@SpringBootTest
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class StoreFeeRateIntegrationTest {

    private static final long PRICE = 10000; // 5% → 수수료 500 / 3% → 300 / 10% → 1000

    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private SettlementAggregationService aggregationService;
    @Autowired private AdminSettlementService adminSettlementService;
    @Autowired private AdminPaymentService adminPaymentService;
    @Autowired private AdminStoreService adminStoreService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementStatusHistoryRepository settlementStatusHistoryRepository;
    @Autowired private SettlementAggregationFailureRepository settlementAggregationFailureRepository;
    @Autowired private SettlementRefundRepository settlementRefundRepository;
    @Autowired private SettlementCarryoverRepository settlementCarryoverRepository;

    private StoreResponse store;
    private MenuResponse menu;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 10, 31, 12, 0));
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        menu = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: 차감 기록 → settlements → payments → orders → menus → stores → users
        // 정산을 참조하는 감사 이력·실패 기록(V11)을 먼저 삭제
        settlementStatusHistoryRepository.deleteAllInBatch();
        settlementAggregationFailureRepository.deleteAllInBatch();
        settlementCarryoverRepository.deleteAllInBatch();
        settlementRefundRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ===== 도우미 =====

    private PaymentResponse paidOrder(LocalDateTime paidAt) {
        clock.set(paidAt);
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        return paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
    }

    private void deliver(PaymentResponse payment, LocalDateTime deliveredAt) {
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.ORDER_ACCEPTED);
        clock.set(deliveredAt);
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.DELIVERY_COMPLETED);
    }

    // deliveredAt 1시간 전에 결제, deliveredAt에 배달완료된 결제 id
    private Long delivered(LocalDateTime deliveredAt) {
        PaymentResponse payment = paidOrder(deliveredAt.minusHours(1));
        deliver(payment, deliveredAt);
        return payment.id();
    }

    private void changeFeeRate(String rate) {
        adminStoreService.changeFeeRate(store.id(), new BigDecimal(rate), "admin");
    }

    private void aggregate(LocalDate date) {
        clock.set(date.plusDays(1).atTime(1, 10));
        aggregationService.aggregateDaily(date);
    }

    private Settlement settlement(LocalDate date) {
        return settlementRepository.findByStoreIdAndSettlementDate(store.id(), date).orElseThrow();
    }

    private Map<String, Object> paymentRow(Long paymentId) {
        return jdbc.queryForMap("SELECT fee_rate, fee_amount, settlement_amount FROM payments WHERE id = ?", paymentId);
    }

    private Map<String, Object> settlementRow(Long settlementId) {
        return jdbc.queryForMap("SELECT * FROM settlements WHERE id = ?", settlementId);
    }

    private static void assertPayment(Map<String, Object> row, String rate, long fee, long net) {
        assertThat((BigDecimal) row.get("fee_rate")).isEqualByComparingTo(rate);
        assertThat(row.get("fee_amount")).isEqualTo(fee);
        assertThat(row.get("settlement_amount")).isEqualTo(net);
    }

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static LocalDateTime nov(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0);
    }

    // ===== 테스트 =====

    @Test
    void 매장_수수료율이_없으면_공통_수수료율로_결제된다() {
        Long paymentId = delivered(nov(5, 12));

        assertThat(jdbc.queryForObject("SELECT fee_rate FROM stores WHERE id = ?", BigDecimal.class, store.id()))
                .isNull();
        assertPayment(paymentRow(paymentId), "0.05", 500, 9500);
    }

    @Test
    void 수수료율을_바꾸면_이후_결제부터_적용되고_기존_결제_스냅샷은_그대로다() {
        Long before = delivered(nov(5, 12));   // 공통 5%

        changeFeeRate("0.03");
        Long after = delivered(nov(5, 15));    // 매장 3%

        assertPayment(paymentRow(before), "0.05", 500, 9500);
        assertPayment(paymentRow(after), "0.03", 300, 9700);
        assertThat(jdbc.queryForObject("SELECT fee_rate FROM stores WHERE id = ?", BigDecimal.class, store.id()))
                .isEqualByComparingTo("0.03");
    }

    @Test
    void 수수료율을_해제하면_이후_결제는_다시_공통_수수료율을_사용한다() {
        changeFeeRate("0.03");
        Long reduced = delivered(nov(5, 12));

        AdminStoreFeeRateResponse response = adminStoreService.clearFeeRate(store.id(), "admin");
        Long fallback = delivered(nov(5, 15));

        assertThat(response.defaultFeeRateApplied()).isTrue();
        assertThat(response.effectiveFeeRate()).isEqualByComparingTo("0.05");
        assertPayment(paymentRow(reduced), "0.03", 300, 9700);
        assertPayment(paymentRow(fallback), "0.05", 500, 9500);
    }

    @Test
    void 결제_후_배달완료_전에_수수료율이_바뀌어도_결제_시점_수수료율로_정산된다() {
        PaymentResponse payment = paidOrder(nov(5, 11));   // 5%로 결제
        changeFeeRate("0.10");                              // 배달 전 변경
        deliver(payment, nov(5, 12));

        aggregate(nov(5));

        assertPayment(paymentRow(payment.id()), "0.05", 500, 9500);
        Settlement nov5 = settlement(nov(5));
        assertThat(nov5.getTotalFee()).isEqualTo(500);
        assertThat(nov5.getSalesSettlementAmount()).isEqualTo(9500);
    }

    @Test
    void 같은_날_수수료율이_다른_결제가_섞여도_정산은_결제별_스냅샷의_합이다() {
        delivered(nov(5, 10));     // 5%
        changeFeeRate("0.03");
        delivered(nov(5, 14));     // 3%
        changeFeeRate("0");
        delivered(nov(5, 18));     // 0%

        aggregate(nov(5));

        Settlement nov5 = settlement(nov(5));
        assertThat(nov5.getPaymentCount()).isEqualTo(3);
        assertThat(nov5.getTotalAmount()).isEqualTo(30000);
        assertThat(nov5.getTotalFee()).isEqualTo(500 + 300);
        assertThat(nov5.getSalesSettlementAmount()).isEqualTo(9500 + 9700 + 10000);
        assertThat(nov5.getSettlementAmount()).isEqualTo(29200);
        assertThat(nov5.getTotalFee() + nov5.getSalesSettlementAmount()).isEqualTo(nov5.getTotalAmount());
    }

    @Test
    void 확정_지급된_정산과_결제는_수수료율을_바꿔도_바뀌지_않는다() {
        Long paymentId = delivered(nov(5, 12));
        aggregate(nov(5));
        Long nov5Id = settlement(nov(5)).getId();
        clock.set(nov(6, 9));
        adminSettlementService.confirm(nov5Id, "admin");
        clock.set(nov(6, 10));
        adminSettlementService.pay(nov5Id, "admin");
        Map<String, Object> settlementBefore = settlementRow(nov5Id);
        Map<String, Object> paymentBefore = paymentRow(paymentId);

        changeFeeRate("0.10");
        adminStoreService.clearFeeRate(store.id(), "admin");
        changeFeeRate("0.01");

        assertThat(settlementRow(nov5Id)).isEqualTo(settlementBefore);
        assertThat(paymentRow(paymentId)).isEqualTo(paymentBefore);
    }

    @Test
    void 수수료율_변경_후_환불해도_결제_시점_수수료율_기준으로_차감된다() {
        Long paymentId = delivered(nov(5, 12));   // 5%: 수수료 500, 정산액 9500
        aggregate(nov(5));
        changeFeeRate("0.03");

        clock.set(nov(6, 10));
        adminPaymentService.refund(paymentId, "admin");
        delivered(nov(6, 15));                     // 3%: 수수료 300, 정산액 9700
        aggregate(nov(6));

        Settlement nov6 = settlement(nov(6));
        assertThat(nov6.getRefundFee()).isEqualTo(500);
        assertThat(nov6.getRefundSettlementAmount()).isEqualTo(9500);
        assertThat(nov6.getTotalFee()).isEqualTo(300);
        assertThat(nov6.getSalesSettlementAmount()).isEqualTo(9700);
        assertThat(nov6.getSettlementAmount()).isEqualTo(9700 - 9500);
        assertThat(jdbc.queryForMap("SELECT fee_amount, settlement_amount FROM settlement_refunds WHERE payment_id = ?",
                paymentId)).containsEntry("fee_amount", 500L).containsEntry("settlement_amount", 9500L);
    }

    @Test
    void 수수료율_0과_해제는_DB와_응답에서_구분되고_결제_결과도_다르다() {
        AdminStoreFeeRateResponse zero = adminStoreService.changeFeeRate(store.id(), BigDecimal.ZERO, "admin");
        BigDecimal stored = jdbc.queryForObject("SELECT fee_rate FROM stores WHERE id = ?", BigDecimal.class, store.id());
        Long zeroPayment = delivered(nov(5, 12));

        assertThat(stored).isNotNull().isEqualByComparingTo("0");
        assertThat(zero.feeRate()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(zero.defaultFeeRateApplied()).isFalse();
        assertPayment(paymentRow(zeroPayment), "0", 0, PRICE);

        AdminStoreFeeRateResponse cleared = adminStoreService.clearFeeRate(store.id(), "admin");
        Long clearedPayment = delivered(nov(5, 15));

        assertThat(jdbc.queryForObject("SELECT fee_rate FROM stores WHERE id = ?", BigDecimal.class, store.id()))
                .isNull();
        assertThat(cleared.feeRate()).isNull();
        assertThat(cleared.effectiveFeeRate()).isEqualByComparingTo("0.05");
        assertThat(cleared.defaultFeeRateApplied()).isTrue();
        assertPayment(paymentRow(clearedPayment), "0.05", 500, 9500);
    }

    @Test
    void 공개_매장_응답에는_수수료율이_없다() {
        changeFeeRate("0.03");

        StoreResponse response = storeService.getStore(store.id());

        assertThat(Arrays.stream(StoreResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("feeRate", "effectiveFeeRate");
        assertThat(response.id()).isEqualTo(store.id());
    }
}
