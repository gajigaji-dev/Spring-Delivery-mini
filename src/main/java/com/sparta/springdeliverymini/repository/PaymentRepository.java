package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    // 해당 주문에 이미 결제 내역이 있는지 확인 (중복 결제 방지)
    // 취소·환불된 결제도 같은 행에 남으므로 상태와 관계없이 확인 (취소된 주문은 다시 결제할 수 없음)
    // TODO(@ManyToOne 전환): 재결제를 허용하게 되면 완료된 결제만 확인하도록 변경
    boolean existsByOrderId(Long orderId);

    // 주문의 결제 (결제완료 주문을 취소할 때 결제도 함께 취소)
    Optional<Payment> findByOrderId(Long orderId);

    // 환불용 조회 — 행 잠금(SELECT ... FOR UPDATE)
    // 같은 결제를 동시에 환불하면 뒤 요청은 앞 요청이 커밋될 때까지 기다린 뒤 REFUNDED를 읽고 409 → 환불은 한 번만 성공
    // (주문은 함께 조회하지 않음 — JOIN하면 orders 행까지 잠기므로. 배달완료 주문은 최종 상태라 잠글 필요 없음)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    // 매장의 정산 대상 결제(판매) — 배달완료 시각이 [start, end) 구간인 배달완료 주문의 결제
    // 시작 포함·끝 미포함 → 기간을 이어 붙여도 한 건이 두 기간에 들어가거나 빠지지 않음
    // 주문을 함께 조회(fetch join)해 건별 내역에서 배달완료 시각을 읽을 때 추가 쿼리가 나가지 않게 함
    // (store_id, delivered_at) 인덱스 사용 (V6)
    // 나중에 환불된 결제도 판매분에는 계속 포함 → 이미 조회·집계된 날짜의 금액이 바뀌지 않음
    //   (환불은 findUnsettledRefunds로 따로 차감. 취소된 결제는 주문이 ORDER_CANCELLED라 원래 대상이 아님)
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.order o
            WHERE o.store.id = :storeId
              AND p.status IN (com.sparta.springdeliverymini.entity.PaymentStatus.COMPLETED,
                               com.sparta.springdeliverymini.entity.PaymentStatus.REFUNDED)
              AND o.status = com.sparta.springdeliverymini.entity.OrderStatus.DELIVERY_COMPLETED
              AND o.deliveredAt >= :start
              AND o.deliveredAt < :end
            ORDER BY o.deliveredAt, p.id
            """)
    List<Payment> findSettlementTargets(
            @Param("storeId") Long storeId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // [start, end) 구간에 정산 대상 결제(판매)가 있는 매장 id — 정산 집계 배치가 집계할 매장을 고를 때 사용
    // 조건은 findSettlementTargets와 같음 (매장 조건만 없음)
    // 미차감 환불만 있는 매장은 findUnsettledRefundStoreIds로 따로 찾아 합침
    @Query("""
            SELECT DISTINCT o.store.id FROM Payment p
            JOIN p.order o
            WHERE p.status IN (com.sparta.springdeliverymini.entity.PaymentStatus.COMPLETED,
                               com.sparta.springdeliverymini.entity.PaymentStatus.REFUNDED)
              AND o.status = com.sparta.springdeliverymini.entity.OrderStatus.DELIVERY_COMPLETED
              AND o.deliveredAt >= :start
              AND o.deliveredAt < :end
            ORDER BY o.store.id
            """)
    List<Long> findSettlementStoreIds(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // 매장의 미차감 환불 — end 전에 환불됐고 아직 어떤 정산에서도 차감되지 않은 환불 (docs/settlement-policy.md 9장)
    // 날짜 구간이 아니라 "차감 기록(settlement_refunds)이 없는지"로 찾음
    //   → 환불일 정산이 먼저 만들어진 뒤 커밋된 환불, catch-up 기간을 넘긴 환불도 다음 집계에서 차감됨
    // refunded_at < end → 환불보다 이른 날짜의 정산에서는 차감하지 않음
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.order o
            WHERE o.store.id = :storeId
              AND p.status = com.sparta.springdeliverymini.entity.PaymentStatus.REFUNDED
              AND p.refundedAt < :end
              AND NOT EXISTS (SELECT 1 FROM SettlementRefund r WHERE r.payment = p)
            ORDER BY p.refundedAt, p.id
            """)
    List<Payment> findUnsettledRefunds(
            @Param("storeId") Long storeId,
            @Param("end") LocalDateTime end);

    // 미차감 환불이 있는 매장 id — 판매가 없는 날에도 환불을 차감할 정산을 만들기 위해 집계 대상에 포함
    @Query("""
            SELECT DISTINCT o.store.id FROM Payment p
            JOIN p.order o
            WHERE p.status = com.sparta.springdeliverymini.entity.PaymentStatus.REFUNDED
              AND p.refundedAt < :end
              AND NOT EXISTS (SELECT 1 FROM SettlementRefund r WHERE r.payment = p)
            ORDER BY o.store.id
            """)
    List<Long> findUnsettledRefundStoreIds(@Param("end") LocalDateTime end);

    // 사장님 정산 조회용 — 환불 시각이 [start, end) 구간인 매장의 환불 (환불이 발생한 날 기준)
    // 실제로 어느 정산에서 차감됐는지와는 별개 (관리자 정산 API에서 확인)
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.order o
            WHERE o.store.id = :storeId
              AND p.status = com.sparta.springdeliverymini.entity.PaymentStatus.REFUNDED
              AND p.refundedAt >= :start
              AND p.refundedAt < :end
            ORDER BY p.refundedAt, p.id
            """)
    List<Payment> findRefundsInPeriod(
            @Param("storeId") Long storeId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
