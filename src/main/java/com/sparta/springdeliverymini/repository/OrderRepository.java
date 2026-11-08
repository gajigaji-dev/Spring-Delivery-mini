package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    // JPA Query Methods 사용하기
    // CUSTOMER가 본인의 주문만 조회
    List<Order> findAllByUserId(Long userId);

    // OWNER가 본인의 메뉴에 들어온 주문만 조회
    List<Order> findAllByMenuOwnerId(Long ownerId);
}
