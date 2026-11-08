package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.SettlementCarryover;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SettlementCarryoverRepository extends JpaRepository<SettlementCarryover, Long> {

    // 정산이 이월받은 기록 목록
    List<SettlementCarryover> findAllByToSettlementId(Long toSettlementId);

    // 사장님 일 정산 상세 — 이 정산이 이월받은 기록 (원래 정산을 함께 조회, 원래 정산일 순)
    @Query("""
            SELECT c FROM SettlementCarryover c
            JOIN FETCH c.fromSettlement f
            WHERE c.toSettlement.id = :settlementId
            ORDER BY f.settlementDate, f.id
            """)
    List<SettlementCarryover> findCarriedIn(@Param("settlementId") Long settlementId);

    // 사장님 일 정산 상세 — 이 정산이 넘긴 이월분이 차감된 기록 (아직 차감 전이면 비어 있음)
    @Query("""
            SELECT c FROM SettlementCarryover c
            JOIN FETCH c.toSettlement
            WHERE c.fromSettlement.id = :settlementId
            """)
    Optional<SettlementCarryover> findCarriedOut(@Param("settlementId") Long settlementId);
}
