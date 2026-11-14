package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    // 목록 조회는 모두 페이지 단위 (#40) — 정렬(id 오름차순)과 크기는 PageQuery.toPageable()에서 지정
    // Slice: 전체 개수를 세지 않고 size + 1건만 읽어 다음 페이지 여부를 판단
    // 응답에 매장 이름이 들어가므로 매장을 같은 쿼리에서 함께 조회(@EntityGraph) → 주문마다 매장을 따로 조회하지 않음
    // 주문 항목(1:N)은 fetch join하면 페이징이 메모리에서 처리되므로 Order.orderItems의 @BatchSize로 한 번에 조회

    // CUSTOMER가 본인의 주문만 조회 — 인덱스 idx_orders_user_id_id (V12)
    @EntityGraph(attributePaths = "store")
    Slice<Order> findAllByUserId(Long userId, Pageable pageable);

    // OWNER가 본인의 모든 매장에 들어온 주문 조회 (orders.store_id → stores.owner_id)
    @EntityGraph(attributePaths = "store")
    Slice<Order> findAllByStoreOwnerId(Long ownerId, Pageable pageable);

    // 특정 매장에 들어온 주문 조회 (소유권 검증은 Service에서 먼저 처리)
    // 매장은 소유권 검증 때 이미 조회되어 있으므로 함께 조회하지 않음 — 인덱스 idx_orders_store_id_id (V12)
    Slice<Order> findAllByStoreId(Long storeId, Pageable pageable);

    // 상태 변경용 조회 — 주문 행 잠금(SELECT ... FOR UPDATE)
    // 결제·취소·수락·배달완료가 같은 주문에 동시에 들어와도 한 번에 하나씩 처리
    //   → 예) 고객 취소와 사장님 수락이 함께 결제완료 상태를 읽고 둘 다 성공하는 일을 막음
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}
