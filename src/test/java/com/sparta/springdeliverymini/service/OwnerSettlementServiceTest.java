package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.OwnerSettlementDetailResponse;
import com.sparta.springdeliverymini.dto.OwnerSettlementListResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

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

// OwnerSettlementService — 권한·소유권, 기간 검증, 상태 필터, 다른 매장 정산 404, 이월 차감 전 표시 (DB 없이)
@ExtendWith(MockitoExtension.class)
class OwnerSettlementServiceTest {

    @Mock private SettlementRepository settlementRepository;
    @Mock private SettlementRefundRepository settlementRefundRepository;
    @Mock private SettlementCarryoverRepository settlementCarryoverRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private StoreService storeService;

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    private OwnerSettlementService service;
    private final User owner = new User("owner1", "pw", Role.OWNER);
    private Store store;

    @BeforeEach
    void setUp() {
        service = new OwnerSettlementService(settlementRepository, settlementRefundRepository,
                settlementCarryoverRepository, paymentRepository, userRepository, storeService);
        ReflectionTestUtils.setField(owner, "id", 1L);
        store = new Store("치킨집", owner);
        ReflectionTestUtils.setField(store, "id", 20L);
    }

    private void givenOwner() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeService.getOwnedStore(20L, owner)).willReturn(store);
    }

    // 환불이 판매보다 커서 지급액 0, 다음으로 2000원 이월한 정산
    private Settlement settlementWithCarryOut() {
        Settlement settlement = Settlement.aggregate(store, LocalDate.of(2026, 11, 5),
                new SettlementTotals(1, 10000, 500, 9500),
                new SettlementTotals(1, 12000, 500, 11500),
                0, LocalDateTime.of(2026, 11, 6, 1, 10));
        ReflectionTestUtils.setField(settlement, "id", 7L);
        return settlement;
    }

    @Test
    void 상태가_없으면_전체_있으면_해당_상태만_조회한다() {
        givenOwner();
        given(settlementRepository.findAllByStoreIdAndSettlementDateBetweenOrderBySettlementDate(20L, FROM, TO))
                .willReturn(List.of(settlementWithCarryOut()));

        OwnerSettlementListResponse all = service.getDailySettlements(20L, FROM, TO, null, "owner1");
        service.getDailySettlements(20L, FROM, TO, SettlementStatus.PAID, "owner1");

        assertThat(all.storeName()).isEqualTo("치킨집");
        assertThat(all.settlements()).hasSize(1);
        assertThat(all.settlements().get(0).status()).isEqualTo(SettlementStatus.PENDING); // 확정 대기도 노출
        assertThat(all.settlements().get(0).carriedOutAmount()).isEqualTo(2000);
        verify(settlementRepository).findAllByStoreIdAndSettlementDateBetweenAndStatusOrderBySettlementDate(
                20L, FROM, TO, SettlementStatus.PAID);
    }

    @Test
    void 기간_규칙을_어기면_400이고_조회하지_않는다() {
        givenOwner();

        assertThatThrownBy(() -> service.getDailySettlements(20L, TO, FROM, null, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.getDailySettlements(20L, FROM, FROM.plusDays(31), null, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(settlementRepository);
    }

    @Test
    void OWNER가_아니면_403이고_매장을_조회하지_않는다() {
        given(userRepository.findByUsername("customer1"))
                .willReturn(Optional.of(new User("customer1", "pw", Role.CUSTOMER)));

        assertThatThrownBy(() -> service.getDailySettlement(20L, 7L, "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(storeService, never()).getOwnedStore(anyLong(), any());
    }

    @Test
    void 본인_매장이_아니면_StoreService의_403을_그대로_전달한다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeService.getOwnedStore(99L, owner))
                .willThrow(new ApiException(HttpStatus.FORBIDDEN, "본인 매장이 아닙니다."));

        assertThatThrownBy(() -> service.getDailySettlements(99L, FROM, TO, null, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(settlementRepository);
    }

    @Test
    void 다른_매장의_정산이면_404() {
        givenOwner();
        given(settlementRepository.findByIdAndStoreId(8L, 20L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDailySettlement(20L, 8L, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 넘긴_이월분이_아직_차감되지_않았으면_차감_전으로_표시한다() {
        givenOwner();
        given(settlementRepository.findByIdAndStoreId(7L, 20L)).willReturn(Optional.of(settlementWithCarryOut()));
        given(settlementCarryoverRepository.findCarriedOut(7L)).willReturn(Optional.empty());

        OwnerSettlementDetailResponse detail = service.getDailySettlement(20L, 7L, "owner1");

        assertThat(detail.settlement().settlementAmount()).isZero();
        assertThat(detail.carriedOut().amount()).isEqualTo(2000);
        assertThat(detail.carriedOut().deducted()).isFalse();
        assertThat(detail.carriedOut().toSettlementId()).isNull();
        // 판매 조회는 정산일 하루 구간
        verify(paymentRepository).findSettlementTargets(20L,
                LocalDate.of(2026, 11, 5).atStartOfDay(), LocalDate.of(2026, 11, 6).atStartOfDay());
    }
}
