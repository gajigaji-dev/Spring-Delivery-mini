package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.SettlementAggregationFailure;
import com.sparta.springdeliverymini.entity.SettlementAggregationFailureStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementAggregationFailureRepository extends JpaRepository<SettlementAggregationFailure, Long> {

    // 매장·날짜의 실패 기록 (UNIQUE) — 호출하는 쪽이 매장 행 잠금을 잡은 상태에서만 갱신
    Optional<SettlementAggregationFailure> findByStoreIdAndSettlementDate(Long storeId, LocalDate settlementDate);

    // 관리자 목록 조회 (최근 실패 순) — 응답에 매장·처리자 이름을 담으므로 함께 조회(N+1 방지)
    @EntityGraph(attributePaths = {"store", "lastFailedBy", "resolvedBy"})
    List<SettlementAggregationFailure> findAllByOrderByLastFailedAtDescIdDesc();

    @EntityGraph(attributePaths = {"store", "lastFailedBy", "resolvedBy"})
    List<SettlementAggregationFailure> findAllByStatusOrderByLastFailedAtDescIdDesc(
            SettlementAggregationFailureStatus status);
}
