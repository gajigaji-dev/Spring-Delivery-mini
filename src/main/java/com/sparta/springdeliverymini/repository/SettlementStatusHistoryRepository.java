package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.SettlementStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SettlementStatusHistoryRepository extends JpaRepository<SettlementStatusHistory, Long> {

    // 정산들의 상태 변경 이력 (기록 순) — 응답에 처리자 이름을 담으므로 처리자를 함께 조회(N+1 방지)
    // 스케줄러 집계 이력은 처리자가 없으므로 LEFT JOIN
    @Query("""
            SELECT h FROM SettlementStatusHistory h
            LEFT JOIN FETCH h.actor
            WHERE h.settlement.id IN :settlementIds
            ORDER BY h.id
            """)
    List<SettlementStatusHistory> findAllWithActor(@Param("settlementIds") Collection<Long> settlementIds);
}
