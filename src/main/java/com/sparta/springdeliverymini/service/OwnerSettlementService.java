package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.MySettlementResponse;
import com.sparta.springdeliverymini.dto.OwnerSettlementDetailResponse;
import com.sparta.springdeliverymini.dto.OwnerSettlementListResponse;
import com.sparta.springdeliverymini.dto.OwnerSettlementResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.dto.SettlementItemResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
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
    private final StoreRepository storeRepository;
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

    // 내 정산 내역 (GET /api/settlements) — 본인 매장 전체의 일 정산을 페이지 단위로 (id 오름차순, docs/performance.md)
    // - storeId를 주면 그 매장만 (없는 매장 404, 남의 매장 403 — 매장별 조회와 같은 규칙)
    // - 대상 매장을 먼저 본인 매장으로 확정한 뒤 store_id IN (...)으로 조회 → 다른 매장의 정산은 조건상 섞일 수 없음
    // - 매장별 목록과 달리 기간이 선택이라 31일 제한 없음 (페이징으로 응답 크기 제한)
    public Slice<MySettlementResponse> getMySettlements(String username, Long storeId, SettlementStatus status,
                                                        LocalDate from, LocalDate to, PageQuery pageQuery) {
        User owner = currentOwner(username);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "시작일은 종료일보다 늦을 수 없습니다.");
        }

        // 매장들은 이 트랜잭션의 영속성 컨텍스트에 올라가 있으므로 정산의 store를 꺼낼 때 추가 조회 없음 (N+1 없음)
        List<Store> stores = storeId != null
                ? List.of(storeService.getOwnedStore(storeId, owner))
                : storeRepository.findAllByOwnerId(owner.getId());
        Pageable pageable = pageQuery.toPageable();
        if (stores.isEmpty()) {
            // 매장이 없는 점주 — 빈 IN 목록으로 쿼리하지 않음
            return new SliceImpl<>(List.of(), pageable, false);
        }

        List<Long> storeIds = stores.stream().map(Store::getId).toList();
        return settlementRepository
                .findBy(mySettlementsSpec(storeIds, status, from, to), query -> query.slice(pageable))
                .map(MySettlementResponse::from);
    }

    // 선택 조건은 값이 있을 때만 붙임 (정산일은 from·to 모두 포함)
    private static Specification<Settlement> mySettlementsSpec(List<Long> storeIds, SettlementStatus status,
                                                               LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("store").get("id").in(storeIds));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("settlementDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("settlementDate"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
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
        return storeService.getOwnedStore(storeId, currentOwner(username));
    }

    private User currentOwner(String username) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        // SecurityConfig에서도 막지만, 토큰 발급 후 역할 변경에 대비해 한 번 더 확인
        if (currentUser.getRole() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER만 정산을 조회할 수 있습니다.");
        }
        return currentUser;
    }
}
