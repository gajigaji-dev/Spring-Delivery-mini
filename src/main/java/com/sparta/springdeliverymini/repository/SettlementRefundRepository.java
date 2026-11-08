package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.SettlementRefund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementRefundRepository extends JpaRepository<SettlementRefund, Long> {

    // 정산에서 차감한 환불 목록
    List<SettlementRefund> findAllBySettlementId(Long settlementId);
}
