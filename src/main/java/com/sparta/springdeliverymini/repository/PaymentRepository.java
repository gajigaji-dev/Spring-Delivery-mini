package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    // 해당 주문에 이미 결제 내역이 있는지 확인 (중복 결제 방지)
    // TODO(@ManyToOne 전환): 실패·취소 기록도 남게 되므로 성공한 결제만 확인하도록 변경
    //  예) existsByOrderIdAndStatus(orderId, PaymentStatus.SUCCESS)
    boolean existsByOrderId(Long orderId);
}
