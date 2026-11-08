package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    // 해당 주문에 이미 결제 내역이 있는지 확인 (중복 결제 방지)
    // TODO(@ManyToOne 전환): 실패·취소 기록도 남게 되므로 성공한 결제만 확인하도록 변경
    //  예) existsByOrderIdAndStatus(orderId, PaymentStatus.SUCCESS)
    boolean existsByOrderId(Long orderId);

    // 매장의 정산 대상 결제 — 배달완료 시각이 [start, end) 구간인 배달완료 주문의 결제
    // 시작 포함·끝 미포함 → 기간을 이어 붙여도 한 건이 두 기간에 들어가거나 빠지지 않음
    // 주문을 함께 조회(fetch join)해 건별 내역에서 배달완료 시각을 읽을 때 추가 쿼리가 나가지 않게 함
    // (store_id, delivered_at) 인덱스 사용 (V6)
    // TODO(@ManyToOne 전환): 성공한 결제만 정산 대상 → p.status = SUCCESS 조건 추가
    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.order o
            WHERE o.store.id = :storeId
              AND o.status = com.sparta.springdeliverymini.entity.OrderStatus.DELIVERY_COMPLETED
              AND o.deliveredAt >= :start
              AND o.deliveredAt < :end
            ORDER BY o.deliveredAt, p.id
            """)
    List<Payment> findSettlementTargets(
            @Param("storeId") Long storeId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
