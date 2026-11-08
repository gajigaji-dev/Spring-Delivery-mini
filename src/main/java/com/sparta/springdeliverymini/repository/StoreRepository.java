package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Store;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    // 정산 집계용 — 매장 행 잠금(SELECT ... FOR UPDATE)
    // 같은 매장의 집계(서로 다른 날짜 포함)를 한 번에 하나씩 → 미차감 환불·이월을 두 집계가 함께 읽지 않음
    // (중복 차감의 최종 보장은 settlement_refunds·settlement_carryovers의 UNIQUE 제약)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Store s WHERE s.id = :id")
    Optional<Store> findByIdForUpdate(@Param("id") Long id);
}
