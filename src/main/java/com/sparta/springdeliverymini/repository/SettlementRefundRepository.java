package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.SettlementRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SettlementRefundRepository extends JpaRepository<SettlementRefund, Long> {

    // 정산에서 차감한 환불 목록
    List<SettlementRefund> findAllBySettlementId(Long settlementId);

    // 사장님 일 정산 상세용 — 결제·주문을 함께 조회(N+1 방지), 환불 시각 순
    @Query("""
            SELECT r FROM SettlementRefund r
            JOIN FETCH r.payment p
            JOIN FETCH p.order
            WHERE r.settlement.id = :settlementId
            ORDER BY p.refundedAt, p.id
            """)
    List<SettlementRefund> findDeductionsWithPayment(@Param("settlementId") Long settlementId);
}
