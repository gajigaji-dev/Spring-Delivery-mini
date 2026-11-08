package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.SettlementCarryover;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementCarryoverRepository extends JpaRepository<SettlementCarryover, Long> {

    // 정산이 이월받은 기록 목록
    List<SettlementCarryover> findAllByToSettlementId(Long toSettlementId);
}
