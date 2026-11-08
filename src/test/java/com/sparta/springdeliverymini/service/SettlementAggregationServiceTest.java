package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.service.SettlementAggregationService.DailyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

// SettlementAggregationService의 집계·중복 방지·오류 격리·환불/이월 반영 흐름 검증 (DB 없이)
@ExtendWith(MockitoExtension.class)
class SettlementAggregationServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private SettlementRepository settlementRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private SettlementRefundRepository settlementRefundRepository;
    @Mock private SettlementCarryoverRepository settlementCarryoverRepository;
    @Mock private PlatformTransactionManager transactionManager;

    // 11/6 01:10 → 11/5까지 집계 가능
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 1, 10);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 5, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 6, 0, 0);

    private final Store store = new Store("치킨집", new User("owner1", "pw", Role.OWNER));

    private SettlementAggregationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        // Mock 트랜잭션 매니저로 TransactionTemplate을 만들어 콜백만 실행
        service = new SettlementAggregationService(paymentRepository, settlementRepository, storeRepository,
                settlementRefundRepository, settlementCarryoverRepository, new TransactionTemplate(transactionManager), clock);
    }

    private static Payment payment(long amount) {
        return new Payment(null, amount, new BigDecimal("0.0500"), PaymentMethod.CARD, LocalDateTime.of(2026, 11, 5, 12, 0));
    }

    // 매장(store)의 배달완료 주문 결제 → refundedAt에 환불
    private Payment refundedPayment(long amount, LocalDateTime refundedAt) {
        Order order = new Order(new User("customer1", "pw", Role.CUSTOMER), store, "서울시", OrderStatus.DELIVERY_COMPLETED);
        ReflectionTestUtils.setField(order, "deliveredAt", LocalDateTime.of(2026, 11, 1, 12, 0));
        Payment payment = new Payment(order, amount, new BigDecimal("0.0500"), PaymentMethod.CARD, LocalDateTime.of(2026, 11, 1, 11, 0));
        payment.refund(refundedAt);
        return payment;
    }

    private void givenStore(Long storeId) {
        given(storeRepository.findByIdForUpdate(storeId)).willReturn(Optional.of(store));
    }

    private Settlement savedSettlement() {
        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void 정산_대상이_있는_매장마다_하루치를_PENDING으로_집계한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        givenStore(10L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false);
        given(paymentRepository.findSettlementTargets(10L, START, END))
                .willReturn(List.of(payment(18009), payment(20000)));

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 1, 0, 0));
        Settlement saved = savedSettlement();
        assertThat(saved.getStore()).isSameAs(store);
        assertThat(saved.getSettlementDate()).isEqualTo(NOV_5);
        assertThat(saved.getPaymentCount()).isEqualTo(2);
        assertThat(saved.getTotalAmount()).isEqualTo(38009L);
        assertThat(saved.getTotalFee()).isEqualTo(1900L);
        assertThat(saved.getSalesSettlementAmount()).isEqualTo(36109L);
        assertThat(saved.getSettlementAmount()).isEqualTo(36109L);
        assertThat(saved.getRefundCount()).isZero();
        assertThat(saved.getCarriedInAmount()).isZero();
        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(saved.getConfirmedAt()).isNull(); // 확정은 관리자가
        assertThat(saved.getPaidAt()).isNull();
    }

    @Test
    void 매장_행을_잠근_뒤에_중복_여부를_확인한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        givenStore(10L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willReturn(List.of(payment(10000)));

        service.aggregateDaily(NOV_5);

        var inOrder = inOrder(storeRepository, settlementRepository, paymentRepository);
        inOrder.verify(storeRepository).findByIdForUpdate(10L);
        inOrder.verify(settlementRepository).existsByStoreIdAndSettlementDate(10L, NOV_5);
        inOrder.verify(paymentRepository).findSettlementTargets(10L, START, END);
    }

    @Test
    void 이미_정산이_있는_매장은_건너뛰고_기존_정산은_건드리지_않는다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        givenStore(10L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(true);

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 1, 0));
        verify(paymentRepository, never()).findSettlementTargets(anyLong(), any(), any());
        verify(paymentRepository, never()).findUnsettledRefunds(anyLong(), any());
        verify(settlementRepository, never()).saveAndFlush(any());
        verifyNoInteractions(settlementRefundRepository, settlementCarryoverRepository);
    }

    @Test
    void 미차감_환불만_있는_매장도_집계_대상에_포함된다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of());
        given(paymentRepository.findUnsettledRefundStoreIds(END)).willReturn(List.of(10L));
        givenStore(10L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false);
        Payment refund = refundedPayment(10000, LocalDateTime.of(2026, 11, 3, 15, 0));
        given(paymentRepository.findUnsettledRefunds(10L, END)).willReturn(List.of(refund));

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 1, 0, 0));
        Settlement saved = savedSettlement();
        assertThat(saved.getPaymentCount()).isZero();
        assertThat(saved.getRefundCount()).isEqualTo(1);
        assertThat(saved.getRefundAmount()).isEqualTo(10000);
        assertThat(saved.getRefundFee()).isEqualTo(500);
        assertThat(saved.getRefundSettlementAmount()).isEqualTo(9500);
        assertThat(saved.getSettlementAmount()).isZero();
        assertThat(saved.getCarriedOutAmount()).isEqualTo(9500); // 다음 정산으로 이월

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRefund>> links = ArgumentCaptor.forClass(List.class);
        verify(settlementRefundRepository).saveAll(links.capture());
        assertThat(links.getValue()).singleElement().satisfies(link -> {
            assertThat(link.getPayment()).isSameAs(refund);
            assertThat(link.getSettlement()).isSameAs(saved);
            assertThat(link.getSettlementAmount()).isEqualTo(9500);
        });
    }

    @Test
    void 판매와_환불_매장_id가_겹쳐도_한_번만_집계한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        given(paymentRepository.findUnsettledRefundStoreIds(END)).willReturn(List.of(10L));
        givenStore(10L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willReturn(List.of(payment(10000)));

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 1, 0, 0));
        verify(storeRepository, times(1)).findByIdForUpdate(10L);
    }

    @Test
    void 미차감_이월분은_이_날짜보다_앞선_정산에서만_가져와_차감한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        givenStore(10L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willReturn(List.of(payment(10000))); // 정산액 9500
        // 11/3 정산에서 3000, 11/4 정산에서 2000 이월
        Settlement nov3 = Settlement.aggregate(store, LocalDate.of(2026, 11, 3), SettlementTotals.EMPTY,
                new SettlementTotals(1, 3158, 158, 3000), 0, NOW);
        Settlement nov4 = Settlement.aggregate(store, LocalDate.of(2026, 11, 4), SettlementTotals.EMPTY,
                new SettlementTotals(1, 2105, 105, 2000), 0, NOW);
        given(settlementRepository.findUnsettledCarryovers(10L, NOV_5)).willReturn(List.of(nov3, nov4));

        service.aggregateDaily(NOV_5);

        Settlement saved = savedSettlement();
        assertThat(saved.getCarriedInAmount()).isEqualTo(5000);
        assertThat(saved.getSettlementAmount()).isEqualTo(4500); // 9500 - 5000
        assertThat(saved.getCarriedOutAmount()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementCarryover>> links = ArgumentCaptor.forClass(List.class);
        verify(settlementCarryoverRepository).saveAll(links.capture());
        assertThat(links.getValue()).extracting(SettlementCarryover::getFromSettlement).containsExactly(nov3, nov4);
        assertThat(links.getValue()).allSatisfy(link -> assertThat(link.getToSettlement()).isSameAs(saved));
        assertThat(links.getValue()).extracting(SettlementCarryover::getAmount).containsExactly(3000L, 2000L);
    }

    @Test
    void 판매도_미차감_환불도_없으면_정산을_만들지_않는다() {
        // 매장 목록을 고른 뒤 다른 집계가 먼저 환불을 가져간 경우 등
        given(paymentRepository.findUnsettledRefundStoreIds(END)).willReturn(List.of(10L));
        givenStore(10L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false);

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 1, 0));
        verify(settlementRepository, never()).saveAndFlush(any());
        verify(settlementRepository, never()).findUnsettledCarryovers(anyLong(), any()); // 이월분만으로는 만들지 않음
    }

    @Test
    void 동시에_먼저_집계된_경우_UNIQUE_위반은_중복으로_보고_건너뛴다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        givenStore(10L);
        // 집계 전 확인: 아직 없음 → 저장 시 UNIQUE 위반 → 다시 확인: 다른 실행이 저장해 둠
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false, true);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willReturn(List.of(payment(10000)));
        given(settlementRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk_settlements_store_date"));

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 1, 0));
    }

    @Test
    void 중복이_아닌_제약_위반은_실패로_기록한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        givenStore(10L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false, false);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willReturn(List.of(payment(10000)));
        given(settlementRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("settlements_sales_amount_check"));

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 0, 1));
    }

    @Test
    void 환불_연결_기록이_UNIQUE_위반이면_정산까지_롤백하고_실패로_기록한다() {
        given(paymentRepository.findUnsettledRefundStoreIds(END)).willReturn(List.of(10L));
        givenStore(10L);
        // 롤백되므로 다시 확인해도 정산은 없음 → 중복이 아니라 실패
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false, false);
        given(paymentRepository.findUnsettledRefunds(10L, END))
                .willReturn(List.of(refundedPayment(10000, LocalDateTime.of(2026, 11, 5, 9, 0))));
        given(settlementRefundRepository.saveAll(any())).willThrow(new DataIntegrityViolationException("uk_settlement_refunds_payment"));

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 0, 1));
        verify(transactionManager).rollback(any());
    }

    @Test
    void 한_매장이_실패해도_다른_매장은_집계한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L, 20L));
        givenStore(10L);
        givenStore(20L);
        given(settlementRepository.existsByStoreIdAndSettlementDate(anyLong(), any())).willReturn(false);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willThrow(new IllegalStateException("DB 오류"));
        given(paymentRepository.findSettlementTargets(20L, START, END)).willReturn(List.of(payment(10000)));

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 1, 0, 1));
        verify(settlementRepository, times(1)).saveAndFlush(any());
        verify(transactionManager).rollback(any()); // 실패한 매장은 롤백
    }

    @Test
    void 집계_가능_시각_전인_날짜는_집계하지_않는다() {
        // 11/6 01:10 기준 11/6은 아직 집계 불가 (11/7 01:00부터)
        assertThatThrownBy(() -> service.aggregateDaily(LocalDate.of(2026, 11, 6)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentRepository, settlementRepository);
    }

    @Test
    void 정산_대상이_없는_날은_아무것도_저장하지_않는다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of());
        given(paymentRepository.findUnsettledRefundStoreIds(END)).willReturn(List.of());

        DailyResult result = service.aggregateDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 0, 0));
        verifyNoInteractions(settlementRepository, storeRepository);
    }
}
