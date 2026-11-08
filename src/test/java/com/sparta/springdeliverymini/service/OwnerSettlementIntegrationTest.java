package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.dto.OwnerSettlementDetailResponse.CarriedOut;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 사장님 일 정산 기록 조회를 실제 PostgreSQL로 검증
// - 목록의 금액·상태가 관리자 정산과 같고, 확정 대기(PENDING)도 보임
// - 늦게 반영된 환불은 실제로 차감된 정산의 상세에 나옴 (기간 조회 API와의 차이)
// - 이월분의 넘긴 쪽·받은 쪽이 서로 연결되고, 차감 전이면 차감 전으로 표시
@SpringBootTest
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class OwnerSettlementIntegrationTest {

    private static final long PRICE = 10000; // 5%: 수수료 500, 정산액 9500

    @Autowired private MutableClock clock;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private SettlementService settlementService;
    @Autowired private SettlementAggregationService aggregationService;
    @Autowired private AdminSettlementService adminSettlementService;
    @Autowired private AdminPaymentService adminPaymentService;
    @Autowired private AdminStoreService adminStoreService;
    @Autowired private OwnerSettlementService ownerSettlementService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementRefundRepository settlementRefundRepository;
    @Autowired private SettlementCarryoverRepository settlementCarryoverRepository;

    private StoreResponse store;
    private MenuResponse menu;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 10, 31, 12, 0));
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("owner2", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        menu = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
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

    private Long delivered(LocalDateTime deliveredAt) {
        clock.set(deliveredAt.minusHours(1));
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        PaymentResponse payment = paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
        orderService.statusOrder(order.id(), "owner1", OrderStatus.ORDER_ACCEPTED);
        clock.set(deliveredAt);
        orderService.statusOrder(order.id(), "owner1", OrderStatus.DELIVERY_COMPLETED);
        return payment.id();
    }

    private void refund(Long paymentId, LocalDateTime refundedAt) {
        clock.set(refundedAt);
        adminPaymentService.refund(paymentId, "admin");
    }

    private void aggregate(LocalDate date) {
        clock.set(date.plusDays(1).atTime(1, 10));
        aggregationService.aggregateDaily(date);
    }

    private Long settlementId(LocalDate date) {
        return settlementRepository.findByStoreIdAndSettlementDate(store.id(), date).orElseThrow().getId();
    }

    private List<OwnerSettlementResponse> list(SettlementStatus status) {
        return ownerSettlementService.getDailySettlements(store.id(), nov(1), nov(30), status, "owner1").settlements();
    }

    private OwnerSettlementDetailResponse detail(LocalDate date) {
        return ownerSettlementService.getDailySettlement(store.id(), settlementId(date), "owner1");
    }

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static LocalDateTime nov(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0);
    }

    // ===== 테스트 =====

    @Test
    void 목록은_확정_대기부터_지급까지_상태와_금액이_관리자_정산과_같다() {
        delivered(nov(5, 12));
        delivered(nov(5, 13));
        aggregate(nov(5));
        delivered(nov(6, 12));
        aggregate(nov(6));
        Long nov5 = settlementId(nov(5));
        clock.set(nov(7, 9));
        adminSettlementService.confirm(nov5, "admin");
        adminSettlementService.pay(nov5, "admin");

        List<OwnerSettlementResponse> all = list(null);
        assertThat(all).extracting(OwnerSettlementResponse::settlementDate).containsExactly(nov(5), nov(6));
        assertThat(all).extracting(OwnerSettlementResponse::status)
                .containsExactly(SettlementStatus.PAID, SettlementStatus.PENDING);
        assertThat(all.get(0).settlementAmount()).isEqualTo(19000);
        assertThat(all.get(0).paidAt()).isEqualTo(nov(7, 9));

        SettlementResponse admin = adminSettlementService.getSettlements(nov(5), null, "admin").get(0);
        OwnerSettlementResponse owner = all.get(0);
        assertThat(owner.settlementAmount()).isEqualTo(admin.settlementAmount());
        assertThat(owner.totalFee()).isEqualTo(admin.totalFee());
        assertThat(owner.confirmedAt()).isEqualTo(admin.confirmedAt());

        assertThat(list(SettlementStatus.PENDING)).extracting(OwnerSettlementResponse::settlementDate)
                .containsExactly(nov(6));
    }

    @Test
    void 확정_전_날짜를_포함해도_조회되고_기록이_없으면_빈_목록이다() {
        clock.set(nov(5, 12));
        // 오늘(11/5)과 미래 날짜 포함 — 기간 조회 API와 달리 400이 아님
        assertThat(ownerSettlementService.getDailySettlements(store.id(), nov(1), nov(20), null, "owner1")
                .settlements()).isEmpty();
    }

    @Test
    void 늦게_반영된_환불은_실제로_차감된_정산의_상세에_나온다() {
        Long oldPayment = delivered(nov(4, 12));
        aggregate(nov(4));
        delivered(nov(5, 12));
        aggregate(nov(5));
        refund(oldPayment, nov(5, 23));  // 환불 시각은 11/5지만 11/5 정산은 이미 집계됨
        delivered(nov(6, 12));
        aggregate(nov(6));

        // 정산 기록: 11/5에는 없고 11/6에서 차감
        assertThat(detail(nov(5)).refundDeductions()).isEmpty();
        OwnerSettlementDetailResponse nov6 = detail(nov(6));
        assertThat(nov6.refundDeductions()).hasSize(1);
        assertThat(nov6.refundDeductions().get(0).paymentId()).isEqualTo(oldPayment);
        assertThat(nov6.refundDeductions().get(0).settlementAmount()).isEqualTo(9500);
        assertThat(nov6.refundDeductions().get(0).refundedAt()).isEqualTo(nov(5, 23));
        assertThat(nov6.settlement().settlementAmount()).isZero(); // 9500 - 9500

        // 기간 조회 API(발생 기준)는 같은 환불을 11/5로 보여줌
        clock.set(nov(7, 9));
        assertThat(settlementService.getStoreSettlement(store.id(), nov(5), nov(5), "owner1").refundCount())
                .isEqualTo(1);
    }

    @Test
    void 넘긴_이월분은_차감_전이면_차감_전으로_차감되면_받은_정산과_연결된다() {
        Long p1 = delivered(nov(5, 12));
        Long p2 = delivered(nov(5, 13));
        aggregate(nov(5));
        delivered(nov(6, 12));            // 11/6 판매 9500
        refund(p1, nov(6, 14));
        refund(p2, nov(6, 15));           // 11/6 환불 19000 → 지급 0, 9500 이월
        aggregate(nov(6));

        CarriedOut pending = detail(nov(6)).carriedOut();
        assertThat(pending.amount()).isEqualTo(9500);
        assertThat(pending.deducted()).isFalse();

        delivered(nov(8, 12));
        delivered(nov(8, 13));            // 11/8 판매 19000
        aggregate(nov(8));

        CarriedOut done = detail(nov(6)).carriedOut();
        assertThat(done.deducted()).isTrue();
        assertThat(done.toSettlementDate()).isEqualTo(nov(8));
        OwnerSettlementDetailResponse nov8 = detail(nov(8));
        assertThat(nov8.carriedIn()).singleElement()
                .satisfies(in -> {
                    assertThat(in.fromSettlementDate()).isEqualTo(nov(6));
                    assertThat(in.amount()).isEqualTo(9500);
                });
        assertThat(nov8.settlement().settlementAmount()).isEqualTo(19000 - 9500);
        assertThat(detail(nov(5)).carriedOut()).isNull(); // 이월이 없는 정산

        // Σ지급액 = Σ판매 정산액 - Σ환불 정산액 + 아직 차감 안 된 이월분(0)
        List<OwnerSettlementResponse> all = list(null);
        long payout = all.stream().mapToLong(OwnerSettlementResponse::settlementAmount).sum();
        long sales = all.stream().mapToLong(OwnerSettlementResponse::salesSettlementAmount).sum();
        long refunds = all.stream().mapToLong(OwnerSettlementResponse::refundSettlementAmount).sum();
        assertThat(payout).isEqualTo(sales - refunds);
    }

    // 조회 기간을 이월 중간에서 잘라도 Σ지급 = Σ판매 - Σ환불 - Σ받은 이월 + Σ넘긴 이월
    // (정산 행마다 성립하는 식(DB CHECK settlements_net_amount_check)의 합 — 기간 밖과 오간 이월이 경계 항으로 남음)
    private void assertWindowLedger(LocalDate from, LocalDate to, long expectedPayout) {
        List<OwnerSettlementResponse> window = ownerSettlementService
                .getDailySettlements(store.id(), from, to, null, "owner1").settlements();
        long payout = window.stream().mapToLong(OwnerSettlementResponse::settlementAmount).sum();
        long sales = window.stream().mapToLong(OwnerSettlementResponse::salesSettlementAmount).sum();
        long refunds = window.stream().mapToLong(OwnerSettlementResponse::refundSettlementAmount).sum();
        long carriedIn = window.stream().mapToLong(OwnerSettlementResponse::carriedInAmount).sum();
        long carriedOut = window.stream().mapToLong(OwnerSettlementResponse::carriedOutAmount).sum();

        assertThat(payout).as("%s ~ %s 지급액", from, to).isEqualTo(expectedPayout);
        assertThat(payout).as("%s ~ %s 항등식", from, to)
                .isEqualTo(sales - refunds - carriedIn + carriedOut);
    }

    @Test
    void 조회_기간을_이월_중간에서_잘라도_기간_회계_항등식이_성립한다() {
        // 11/5 판매 19000 → 11/6 판매 9500 + 환불 19000 → 9500 이월 → 11/8 판매 19000에서 차감
        Long p1 = delivered(nov(5, 12));
        Long p2 = delivered(nov(5, 13));
        aggregate(nov(5));
        delivered(nov(6, 12));
        refund(p1, nov(6, 14));
        refund(p2, nov(6, 15));
        aggregate(nov(6));
        delivered(nov(8, 12));
        delivered(nov(8, 13));
        aggregate(nov(8));

        assertWindowLedger(nov(1), nov(30), 19000 + 0 + 9500); // 이월이 기간 안에서 끝남
        assertWindowLedger(nov(6), nov(6), 0);                  // 넘긴 쪽만 (받은 쪽은 기간 뒤)
        assertWindowLedger(nov(7), nov(8), 9500);               // 받은 쪽만 (넘긴 쪽은 기간 앞)
        assertWindowLedger(nov(5), nov(6), 19000);              // 넘긴 이월이 기간 밖에서 차감
        assertWindowLedger(nov(8), nov(8), 9500);               // 하루짜리 받은 쪽

        // 경계 밖 상대 정산을 상세에서 추적할 수 있음
        assertThat(detail(nov(6)).carriedOut().toSettlementDate()).isEqualTo(nov(8));
        assertThat(detail(nov(8)).carriedIn()).singleElement()
                .satisfies(in -> assertThat(in.fromSettlementDate()).isEqualTo(nov(6)));
    }

    @Test
    void 이월이_아직_차감되지_않은_기간도_항등식이_성립한다() {
        // 11/6에 넘긴 9500이 아직 어떤 정산에서도 차감되지 않은 상태
        Long p1 = delivered(nov(5, 12));
        Long p2 = delivered(nov(5, 13));
        aggregate(nov(5));
        delivered(nov(6, 12));
        refund(p1, nov(6, 14));
        refund(p2, nov(6, 15));
        aggregate(nov(6));

        assertWindowLedger(nov(1), nov(30), 19000);
        assertWindowLedger(nov(6), nov(6), 0);
        assertThat(detail(nov(6)).carriedOut().deducted()).isFalse();
    }

    @Test
    void 상세의_판매_내역은_수수료율이_섞여도_정산_합계와_같다() {
        delivered(nov(5, 10));                                                     // 5%
        adminStoreService.changeFeeRate(store.id(), new BigDecimal("0.03"), "admin");
        Long refunded = delivered(nov(5, 14));                                     // 3%
        aggregate(nov(5));
        refund(refunded, nov(6, 9)); // 나중에 환불돼도 11/5 판매 내역에서 빠지지 않음

        OwnerSettlementDetailResponse nov5 = detail(nov(5));

        assertThat(nov5.sales()).extracting(SettlementItemResponse::feeAmount).containsExactly(500L, 300L);
        assertThat(nov5.sales().stream().mapToLong(SettlementItemResponse::settlementAmount).sum())
                .isEqualTo(nov5.settlement().salesSettlementAmount());
        assertThat(nov5.sales().stream().mapToLong(SettlementItemResponse::feeAmount).sum())
                .isEqualTo(nov5.settlement().totalFee());
    }

    @Test
    void 다른_매장의_정산은_404_다른_점주의_매장은_403() {
        delivered(nov(5, 12));
        aggregate(nov(5));
        Long nov5 = settlementId(nov(5));
        StoreResponse other = storeService.createStore(new StoreCreateRequest("피자집"), "owner1");

        // 본인 소유의 다른 매장 경로로 이 정산을 요청 → 404
        assertThatThrownBy(() -> ownerSettlementService.getDailySettlement(other.id(), nov5, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        // 다른 점주 → 403
        assertThatThrownBy(() -> ownerSettlementService.getDailySettlement(store.id(), nov5, "owner2"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
    }
}
