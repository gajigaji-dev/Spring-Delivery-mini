package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    // JPA Query Methods 사용하기
    // CUSTOMER가 본인의 주문만 조회
    List<Order> findAllByUserId(Long userId);

    // OWNER가 본인의 모든 매장에 들어온 주문 조회 (orders.store_id → stores.owner_id)
    List<Order> findAllByStoreOwnerId(Long ownerId);

    // 특정 매장에 들어온 주문 조회 (소유권 검증은 Service에서 먼저 처리)
    List<Order> findAllByStoreId(Long storeId);

    // 상태 변경용 조회 — 주문 행 잠금(SELECT ... FOR UPDATE)
    // 결제·취소·수락·배달완료가 같은 주문에 동시에 들어와도 한 번에 하나씩 처리
    //   → 예) 고객 취소와 사장님 수락이 함께 결제완료 상태를 읽고 둘 다 성공하는 일을 막음
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}
