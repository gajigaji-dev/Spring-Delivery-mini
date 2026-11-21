package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
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

    // 결제 단건 조회용 (#53) — 응답에 주문 id·주문 상태가 들어가므로 주문을 함께 조회(fetch join)
    // 소유권 확인(order.user.id)도 추가 쿼리 없이 처리됨
    @Query("SELECT p FROM Payment p JOIN FETCH p.order WHERE p.id = :id")
    Optional<Payment> findWithOrderById(@Param("id") Long id);

    // PG 결제 키로 결제 조회 (V20) — 같은 키의 승인 요청이 다시 왔을 때 처음 결과를 돌려주기 위함
    // 응답·소유권 확인에 주문이 필요해 함께 조회 (uk_payments_pg_payment_key 인덱스 사용)
    @Query("SELECT p FROM Payment p JOIN FETCH p.order WHERE p.pgPaymentKey = :paymentKey")
    Optional<Payment> findWithOrderByPgPaymentKey(@Param("paymentKey") String paymentKey);

    // 내 결제 목록 (#62) — 주문자 기준(결제자 = 주문자), id 오름차순 페이지 (PageQuery.toPageable())
    // 응답에 주문 id·주문 상태가 들어가므로 주문을 같은 쿼리에서 함께 조회(@EntityGraph) → 결제마다 주문을 따로 조회하지 않음
    // 실행 계획: idx_orders_user_id_id(V12)로 본인 주문 → payments.order_id UNIQUE 인덱스로 결제 → id 정렬
    //   결제 테이블에 user_id가 없어 새 인덱스로 정렬까지 해결할 수 없음 (근거는 docs/performance.md 10장)
    @EntityGraph(attributePaths = "order")
    Slice<Payment> findAllByOrderUserId(Long userId, Pageable pageable);

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

    // 환불 조회(미차감 환불·기간별 환불)는 PaymentRefundRepository (V21 — 환불 1건 단위)
}
