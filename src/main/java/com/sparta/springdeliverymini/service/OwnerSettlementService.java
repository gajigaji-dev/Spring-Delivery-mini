package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.OwnerSettlementDetailResponse;
import com.sparta.springdeliverymini.dto.OwnerSettlementListResponse;
import com.sparta.springdeliverymini.dto.OwnerSettlementResponse;
import com.sparta.springdeliverymini.dto.SettlementItemResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

// 사장님 일 정산 기록 조회 — 저장된 일 정산(settlements)과 차감 연결 기록 기준 (docs/settlement-policy.md 9장)
// - 기간 정산 조회(SettlementService)는 결제로 다시 계산한 "발생 기준" 내역,
//   여기는 실제 지급액·이월·상태가 담긴 "지급 기준" 기록
// - 읽기만 함 (잠금 없음) — 정산 금액은 집계 후 바뀌지 않고 상태만 PENDING → CONFIRMED → PAID로 진행
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class OwnerSettlementService {

    private final SettlementRepository settlementRepository;
    private final SettlementRefundRepository settlementRefundRepository;
    private final SettlementCarryoverRepository settlementCarryoverRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final StoreService storeService;

    // 일 정산 기록 목록 — from ~ to (최대 31일), status 선택. 확정 대기(PENDING)도 포함
    public OwnerSettlementListResponse getDailySettlements(Long storeId, LocalDate from, LocalDate to,
                                                           SettlementStatus status, String username) {
        Store store = ownedStore(storeId, username);

        SettlementPeriod period;
        try {
            period = SettlementPeriod.range(from, to);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        List<Settlement> settlements = status == null
                ? settlementRepository.findAllByStoreIdAndSettlementDateBetweenOrderBySettlementDate(
                        store.getId(), period.from(), period.to())
                : settlementRepository.findAllByStoreIdAndSettlementDateBetweenAndStatusOrderBySettlementDate(
                        store.getId(), period.from(), period.to(), status);

        return new OwnerSettlementListResponse(store.getId(), store.getName(), period.from(), period.to(),
                settlements.stream().map(OwnerSettlementResponse::from).toList());
    }

    // 일 정산 상세 — 다른 매장의 정산이거나 없으면 404 (다른 매장에 그 정산이 있는지 드러내지 않음)
    public OwnerSettlementDetailResponse getDailySettlement(Long storeId, Long settlementId, String username) {
        Store store = ownedStore(storeId, username);

        Settlement settlement = settlementRepository.findByIdAndStoreId(settlementId, store.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "정산이 없습니다."));

        // 판매: 집계와 같은 조회(그날 배달완료된 결제) → 정산의 판매 합계와 항상 같음
        LocalDate date = settlement.getSettlementDate();
        List<SettlementItemResponse> sales = paymentRepository
                .findSettlementTargets(store.getId(), date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                .stream().map(SettlementItemResponse::from).toList();

        return OwnerSettlementDetailResponse.of(
                settlement,
                sales,
                settlementRefundRepository.findDeductionsWithPayment(settlement.getId()),
                settlementCarryoverRepository.findCarriedIn(settlement.getId()),
                settlementCarryoverRepository.findCarriedOut(settlement.getId()).orElse(null));
    }

    // 회원(401) → OWNER 역할(403) → 매장 존재(404)·소유권(403)
    private Store ownedStore(Long storeId, String username) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        // SecurityConfig에서도 막지만, 토큰 발급 후 역할 변경에 대비해 한 번 더 확인
        if (currentUser.getRole() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER만 정산을 조회할 수 있습니다.");
        }
        return storeService.getOwnedStore(storeId, currentUser);
    }
}
