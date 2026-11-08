package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.SettlementRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.service.SettlementConfirmService.DailyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

// SettlementConfirmService의 확정·중복 방지·오류 격리 흐름 검증 (DB 없이)
@ExtendWith(MockitoExtension.class)
class SettlementConfirmServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private SettlementRepository settlementRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private PlatformTransactionManager transactionManager;

    // 11/6 01:10 → 11/5까지 확정 가능
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 1, 10);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 5, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 6, 0, 0);

    private final Store store = new Store("치킨집", new User("owner1", "pw", Role.OWNER));

    private SettlementConfirmService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        // Mock 트랜잭션 매니저로 TransactionTemplate을 만들어 콜백만 실행
        service = new SettlementConfirmService(paymentRepository, settlementRepository, storeRepository,
                new TransactionTemplate(transactionManager), clock);
    }

    private static Payment payment(long amount) {
        return new Payment(null, amount, new BigDecimal("0.0500"), PaymentMethod.CARD, LocalDateTime.of(2026, 11, 5, 12, 0));
    }

    @Test
    void 정산_대상이_있는_매장마다_하루치를_확정한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false);
        given(paymentRepository.findSettlementTargets(10L, START, END))
                .willReturn(List.of(payment(18009), payment(20000)));
        given(storeRepository.getReferenceById(10L)).willReturn(store);

        DailyResult result = service.confirmDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 1, 0, 0));
        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).saveAndFlush(captor.capture());
        Settlement saved = captor.getValue();
        assertThat(saved.getStore()).isSameAs(store);
        assertThat(saved.getSettlementDate()).isEqualTo(NOV_5);
        assertThat(saved.getPaymentCount()).isEqualTo(2);
        assertThat(saved.getTotalAmount()).isEqualTo(38009L);
        assertThat(saved.getTotalFee()).isEqualTo(1900L);
        assertThat(saved.getSettlementAmount()).isEqualTo(36109L);
        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(saved.getConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    void 이미_확정된_매장은_건너뛴다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(true);

        DailyResult result = service.confirmDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 1, 0));
        verify(paymentRepository, never()).findSettlementTargets(anyLong(), any(), any());
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    void 동시에_먼저_확정된_경우_UNIQUE_위반은_중복으로_보고_건너뛴다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        // 확정 전 확인: 아직 없음 → 저장 시 UNIQUE 위반 → 다시 확인: 다른 실행이 저장해 둠
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false, true);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willReturn(List.of(payment(10000)));
        given(storeRepository.getReferenceById(10L)).willReturn(store);
        given(settlementRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk_settlements_store_date"));

        DailyResult result = service.confirmDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 1, 0));
    }

    @Test
    void 중복이_아닌_제약_위반은_실패로_기록한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L));
        given(settlementRepository.existsByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(false, false);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willReturn(List.of(payment(10000)));
        given(storeRepository.getReferenceById(10L)).willReturn(store);
        given(settlementRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("settlements_amount_check"));

        DailyResult result = service.confirmDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 0, 1));
    }

    @Test
    void 한_매장이_실패해도_다른_매장은_확정한다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of(10L, 20L));
        given(settlementRepository.existsByStoreIdAndSettlementDate(anyLong(), any())).willReturn(false);
        given(paymentRepository.findSettlementTargets(10L, START, END)).willThrow(new IllegalStateException("DB 오류"));
        given(paymentRepository.findSettlementTargets(20L, START, END)).willReturn(List.of(payment(10000)));
        given(storeRepository.getReferenceById(20L)).willReturn(store);

        DailyResult result = service.confirmDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 1, 0, 1));
        verify(settlementRepository, times(1)).saveAndFlush(any());
        verify(transactionManager).rollback(any()); // 실패한 매장은 롤백
    }

    @Test
    void 확정_시각_전인_날짜는_확정하지_않는다() {
        // 11/6 01:10 기준 11/6은 아직 확정 불가 (11/7 01:00부터)
        assertThatThrownBy(() -> service.confirmDaily(LocalDate.of(2026, 11, 6)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentRepository, settlementRepository);
    }

    @Test
    void 정산_대상이_없는_날은_아무것도_저장하지_않는다() {
        given(paymentRepository.findSettlementStoreIds(START, END)).willReturn(List.of());

        DailyResult result = service.confirmDaily(NOV_5);

        assertThat(result).isEqualTo(new DailyResult(NOV_5, 0, 0, 0));
        verifyNoInteractions(settlementRepository);
    }
}
