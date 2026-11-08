package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    // 해당 매장·날짜가 이미 확정되었는지 (중복 확정 방지 1차 확인, 최종 보장은 DB UNIQUE)
    boolean existsByStoreIdAndSettlementDate(Long storeId, LocalDate settlementDate);

    Optional<Settlement> findByStoreIdAndSettlementDate(Long storeId, LocalDate settlementDate);

    List<Settlement> findAllBySettlementDate(LocalDate settlementDate);
}
