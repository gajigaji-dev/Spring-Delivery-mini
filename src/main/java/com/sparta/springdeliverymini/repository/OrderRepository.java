package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    // JPA Query Methods 사용하기
    // CUSTOMER가 본인의 주문만 조회
    List<Order> findAllByUserId(Long userId);

    // OWNER가 본인의 모든 매장에 들어온 주문 조회 (orders.store_id → stores.owner_id)
    List<Order> findAllByStoreOwnerId(Long ownerId);

    // 특정 매장에 들어온 주문 조회 (소유권 검증은 Service에서 먼저 처리)
    List<Order> findAllByStoreId(Long storeId);
}
