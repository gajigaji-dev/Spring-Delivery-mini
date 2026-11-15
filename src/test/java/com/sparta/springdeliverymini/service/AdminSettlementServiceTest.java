package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.dto.SettlementResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidSettlementStatusException;
import com.sparta.springdeliverymini.dto.SettlementReaggregationResponse;
import com.sparta.springdeliverymini.repository.SettlementAggregationFailureRepository;
import com.sparta.springdeliverymini.repository.SettlementRepository;
import com.sparta.springdeliverymini.repository.SettlementStatusHistoryRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.service.SettlementAggregationService.Outcome;
import com.sparta.springdeliverymini.service.SettlementAggregationService.StoreResult;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// AdminSettlementService의 관리자 확인·상태 전이·지급 시각 기록 검증 (DB 없이)
@ExtendWith(MockitoExtension.class)
class AdminSettlementServiceTest {

    @Mock private SettlementRepository settlementRepository;
    @Mock private UserRepository userRepository;
    @Mock private SettlementStatusHistoryRepository settlementStatusHistoryRepository;
    @Mock private SettlementAggregationFailureRepository settlementAggregationFailureRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private SettlementAggregationService settlementAggregationService;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 9, 0);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    private AdminSettlementService service;

    private final User admin = new User("admin", "pw", Role.ADMIN);
    private final User owner = new User("owner1", "pw", Role.OWNER);
    private final Store store = store();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        service = new AdminSettlementService(settlementRepository, settlementStatusHistoryRepository,
                settlementAggregationFailureRepository, storeRepository, userRepository,
                settlementAggregationService, clock);
    }

    private Store store() {
        Store store = new Store("치킨집", owner);
        ReflectionTestUtils.setField(store, "id", 10L);
        return store;
    }

    private Settlement pending(Long id) {
        Settlement settlement = Settlement.aggregate(store, NOV_5, new SettlementTotals(1, 10000, 500, 9500),
                LocalDateTime.of(2026, 11, 6, 1, 10));
        ReflectionTestUtils.setField(settlement, "id", id);
        return settlement;
    }

    private Settlement confirmed(Long id) {
        Settlement settlement = pending(id);
        settlement.confirm(LocalDateTime.of(2026, 11, 6, 8, 0));
        return settlement;
    }

    private void loginAs(User user) {
        given(userRepository.findByUsername(user.getUsername())).willReturn(Optional.of(user));
    }

    // ---- 확정 ----

    @Test
    void 관리자는_확정_대기_정산을_확정할_수_있다() {
        loginAs(admin);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(pending(1L)));

        SettlementResponse response = service.confirm(1L, "admin");

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.storeId()).isEqualTo(10L);
        assertThat(response.storeName()).isEqualTo("치킨집");
        assertThat(response.status()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(response.confirmedAt()).isEqualTo(NOW); // Clock 기준 현재 시각
        assertThat(response.paidAt()).isNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 정산_확정과_지급은_상태_변경_로그를_남긴다(CapturedOutput output) {
        loginAs(admin);
        Settlement settlement = pending(1L);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(settlement));

        service.confirm(1L, "admin");
        service.pay(1L, "admin");

        assertThat(output)
                .contains("정산 확정 settlementId=1, storeId=10, settlementDate=2026-11-05, settlementAmount=9500, status=PENDING->CONFIRMED")
                .contains("정산 지급 settlementId=1, storeId=10, settlementDate=2026-11-05, settlementAmount=9500, status=CONFIRMED->PAID");
    }

    @Test
    void 이미_확정된_정산을_다시_확정하면_상태_전이_예외() {
        loginAs(admin);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(confirmed(1L)));

        assertThatThrownBy(() -> service.confirm(1L, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class);
    }

    // ---- 지급 ----

    @Test
    void 관리자는_확정된_정산을_지급할_수_있고_지급_시각이_기록된다() {
        loginAs(admin);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(confirmed(1L)));

        SettlementResponse response = service.pay(1L, "admin");

        assertThat(response.status()).isEqualTo(SettlementStatus.PAID);
        assertThat(response.paidAt()).isEqualTo(NOW);
        assertThat(response.confirmedAt()).isEqualTo(LocalDateTime.of(2026, 11, 6, 8, 0));
    }

    @Test
    void 확정_전_정산을_지급하면_상태_전이_예외() {
        loginAs(admin);
        Settlement settlement = pending(1L);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(settlement));

        assertThatThrownBy(() -> service.pay(1L, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThat(settlement.getPaidAt()).isNull();
    }

    @Test
    void 이미_지급된_정산을_다시_지급하면_상태_전이_예외() {
        loginAs(admin);
        Settlement settlement = confirmed(1L);
        settlement.markPaid(LocalDateTime.of(2026, 11, 6, 8, 30));
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(settlement));

        assertThatThrownBy(() -> service.pay(1L, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThat(settlement.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 11, 6, 8, 30));
    }

    @Test
    void 정산이_없으면_404() {
        loginAs(admin);
        given(settlementRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(99L, "admin"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- 관리자 확인 ----

    @Test
    void 관리자가_아니면_확정_지급_조회_모두_403이고_정산을_조회하지_않는다() {
        loginAs(owner);

        assertThatThrownBy(() -> service.confirm(1L, "owner1"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.pay(1L, "owner1"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.getSettlements(NOV_5, null, "owner1"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(settlementRepository);
    }

    @Test
    void 토큰의_회원이_없으면_401() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(1L, "ghost"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(settlementRepository, never()).findByIdForUpdate(anyLong());
    }

    // ---- 목록 ----

    @Test
    void 상태를_지정하지_않으면_그날_전체_정산을_조회한다() {
        loginAs(admin);
        given(settlementRepository.findAllBySettlementDateOrderById(NOV_5)).willReturn(List.of(pending(1L), confirmed(2L)));

        List<SettlementResponse> responses = service.getSettlements(NOV_5, null, "admin");

        assertThat(responses).extracting(SettlementResponse::id).containsExactly(1L, 2L);
    }

    @Test
    void 상태를_지정하면_그_상태의_정산만_조회한다() {
        loginAs(admin);
        given(settlementRepository.findAllBySettlementDateAndStatusOrderById(NOV_5, SettlementStatus.PENDING))
                .willReturn(List.of(pending(1L)));

        List<SettlementResponse> responses = service.getSettlements(NOV_5, SettlementStatus.PENDING, "admin");

        assertThat(responses).extracting(SettlementResponse::status).containsExactly(SettlementStatus.PENDING);
    }

    // ---- 감사 이력 (#39) ----

    private SettlementStatusHistory savedHistory() {
        ArgumentCaptor<SettlementStatusHistory> captor = ArgumentCaptor.forClass(SettlementStatusHistory.class);
        verify(settlementStatusHistoryRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void 확정하면_인증된_관리자와_확정_시각이_이력으로_저장된다() {
        loginAs(admin);
        Settlement settlement = pending(1L);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(settlement));

        service.confirm(1L, "admin");

        SettlementStatusHistory history = savedHistory();
        assertThat(history.getSettlement()).isSameAs(settlement);
        assertThat(history.getFromStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(history.getActor()).isSameAs(admin); // 토큰의 username으로 DB에서 조회한 회원
        assertThat(history.getChangedAt()).isEqualTo(NOW).isEqualTo(settlement.getConfirmedAt());
    }

    @Test
    void 지급하면_인증된_관리자와_지급_시각이_이력으로_저장된다() {
        loginAs(admin);
        Settlement settlement = confirmed(1L);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(settlement));

        service.pay(1L, "admin");

        SettlementStatusHistory history = savedHistory();
        assertThat(history.getFromStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(history.getToStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(history.getActor()).isSameAs(admin);
        assertThat(history.getChangedAt()).isEqualTo(NOW).isEqualTo(settlement.getPaidAt());
    }

    @Test
    void 상태_전이가_거부되면_이력을_저장하지_않는다() {
        loginAs(admin);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(pending(1L)));

        assertThatThrownBy(() -> service.pay(1L, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class);
        verify(settlementStatusHistoryRepository, never()).save(any());
    }

    @Test
    void 응답의_확정자와_지급자는_이력의_처리자다() {
        loginAs(admin);
        Settlement settlement = confirmed(1L);
        User otherAdmin = new User("admin2", "pw", Role.ADMIN);
        // 확정은 admin2가 했고, 지급은 지금 admin이 함
        SettlementStatusHistory confirmedByOther = SettlementStatusHistory.confirmed(settlement, otherAdmin);
        given(settlementRepository.findByIdForUpdate(1L)).willReturn(Optional.of(settlement));
        given(settlementStatusHistoryRepository.findAllWithActor(any())).willAnswer(invocation ->
                List.of(confirmedByOther, SettlementStatusHistory.paid(settlement, admin)));

        SettlementResponse response = service.pay(1L, "admin");

        assertThat(response.confirmedBy().username()).isEqualTo("admin2");
        assertThat(response.paidBy().username()).isEqualTo("admin");
    }

    // ---- 재집계 (#39) ----

    @Test
    void 재집계는_인증된_관리자를_처리자로_넘기고_매장별_결과를_합산한다() {
        loginAs(admin);
        given(settlementAggregationService.reaggregate(NOV_5, null, admin)).willReturn(List.of(
                new StoreResult(10L, Outcome.CREATED, 1L, null),
                new StoreResult(20L, Outcome.ALREADY_EXISTS, null, null),
                new StoreResult(30L, Outcome.NO_TARGET, null, null),
                new StoreResult(40L, Outcome.FAILED, null, "IllegalStateException: DB 오류")));

        SettlementReaggregationResponse response = service.reaggregate(NOV_5, null, "admin");

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.skipped()).isEqualTo(2); // 날짜 전체 재집계에서는 이미 있는 정산을 건너뛰기만 함
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results()).extracting(SettlementReaggregationResponse.StoreItem::storeId)
                .containsExactly(10L, 20L, 30L, 40L);
    }

    @Test
    void 매장을_지정한_재집계에서_이미_정산이_있으면_상태와_관계없이_409() {
        loginAs(admin);
        given(storeRepository.existsById(10L)).willReturn(true);
        given(settlementAggregationService.reaggregate(NOV_5, 10L, admin))
                .willReturn(List.of(new StoreResult(10L, Outcome.ALREADY_EXISTS, null, null)));
        Settlement paid = confirmed(1L);
        paid.markPaid(LocalDateTime.of(2026, 11, 6, 8, 30));
        given(settlementRepository.findByStoreIdAndSettlementDate(10L, NOV_5)).willReturn(Optional.of(paid));

        assertThatThrownBy(() -> service.reaggregate(NOV_5, 10L, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class)
                .hasMessageContaining("현재 상태: PAID");
    }

    @Test
    void 아직_집계할_수_없는_날짜의_재집계는_400이고_집계하지_않는다() {
        loginAs(admin);

        // 11/6 09:00 기준 11/6은 11/7 01:00부터 집계 가능
        assertThatThrownBy(() -> service.reaggregate(LocalDate.of(2026, 11, 6), null, "admin"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(settlementAggregationService);
    }

    @Test
    void 없는_매장의_재집계는_404이고_집계하지_않는다() {
        loginAs(admin);
        given(storeRepository.existsById(99L)).willReturn(false);

        assertThatThrownBy(() -> service.reaggregate(NOV_5, 99L, "admin"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(settlementAggregationService);
    }

    @Test
    void 관리자가_아니면_재집계_이력_실패_기록_조회_모두_403() {
        loginAs(owner);

        assertThatThrownBy(() -> service.reaggregate(NOV_5, null, "owner1"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.getHistories(1L, "owner1"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.getAggregationFailures(null, "owner1"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(settlementAggregationService, settlementRepository,
                settlementStatusHistoryRepository, settlementAggregationFailureRepository);
    }
}
