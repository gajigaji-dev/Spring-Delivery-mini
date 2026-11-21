package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.PaymentRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

// 환불 1건 (V21) — 정산 차감·사장님 정산 조회의 환불 단위 (V21 전에는 REFUNDED 결제를 직접 찾음)
public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    // 매장의 미차감 환불 — end 전에 환불됐고 아직 어떤 정산에서도 차감되지 않은 환불 1건들 (docs/settlement-policy.md 9장)
    // 날짜 구간이 아니라 "차감 기록(settlement_refunds)이 없는지"로 찾음
    //   → 환불일 정산이 먼저 만들어진 뒤 커밋된 환불, catch-up 기간을 넘긴 환불도 다음 집계에서 차감됨
    // refunded_at < end → 환불보다 이른 날짜의 정산에서는 차감하지 않음
    // 결제·주문을 함께 조회 — 차감 기록의 매장 확인·사장님 화면 표시에 추가 쿼리가 나가지 않도록
    @Query("""
            SELECT r FROM PaymentRefund r
            JOIN FETCH r.payment p
            JOIN FETCH p.order o
            WHERE o.store.id = :storeId
              AND r.refundedAt < :end
              AND NOT EXISTS (SELECT 1 FROM SettlementRefund s WHERE s.paymentRefund = r)
            ORDER BY r.refundedAt, r.id
            """)
    List<PaymentRefund> findUnsettledRefunds(@Param("storeId") Long storeId, @Param("end") LocalDateTime end);

    // 미차감 환불이 있는 매장 id — 판매가 없는 날에도 환불을 차감할 정산을 만들기 위해 집계 대상에 포함
    @Query("""
            SELECT DISTINCT o.store.id FROM PaymentRefund r
            JOIN r.payment p
            JOIN p.order o
            WHERE r.refundedAt < :end
              AND NOT EXISTS (SELECT 1 FROM SettlementRefund s WHERE s.paymentRefund = r)
            ORDER BY o.store.id
            """)
    List<Long> findUnsettledRefundStoreIds(@Param("end") LocalDateTime end);

    // 사장님 정산 조회용 — 환불 시각이 [start, end) 구간인 매장의 환불 1건들 (환불이 발생한 날 기준)
    // 실제로 어느 정산에서 차감됐는지와는 별개 (일 정산 기록 API에서 확인)
    @Query("""
            SELECT r FROM PaymentRefund r
            JOIN FETCH r.payment p
            JOIN FETCH p.order o
            WHERE o.store.id = :storeId
              AND r.refundedAt >= :start
              AND r.refundedAt < :end
            ORDER BY r.refundedAt, r.id
            """)
    List<PaymentRefund> findRefundsInPeriod(@Param("storeId") Long storeId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    // 결제의 환불 내역 (관리자 환불 응답·결제 조회)
    List<PaymentRefund> findAllByPaymentIdOrderByIdAsc(Long paymentId);
}
