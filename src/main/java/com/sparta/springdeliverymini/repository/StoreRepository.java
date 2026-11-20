package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Store;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    // 매장 목록을 페이지 단위로 조회 (#40)
    // JpaRepository.findAll(Pageable)은 Page를 반환하며 전체 개수(count)를 함께 조회하므로, 개수를 세지 않는 Slice로 따로 정의
    Slice<Store> findAllBy(Pageable pageable);

    // 점주의 매장 전체 — 내 정산 내역 조회의 대상 매장 (idx_stores_owner_id, V4)
    List<Store> findAllByOwnerId(Long ownerId);

    // 내 정보 조회(#62)의 매장 id 목록 — 엔티티 없이 id만 (idx_stores_owner_id, V4)
    @Query("SELECT s.id FROM Store s WHERE s.owner.id = :ownerId ORDER BY s.id")
    List<Long> findIdsByOwnerId(@Param("ownerId") Long ownerId);

    // 정산 집계용 — 매장 행 잠금(SELECT ... FOR UPDATE)
    // 같은 매장의 집계(서로 다른 날짜 포함)를 한 번에 하나씩 → 미차감 환불·이월을 두 집계가 함께 읽지 않음
    // (중복 차감의 최종 보장은 settlement_refunds·settlement_carryovers의 UNIQUE 제약)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Store s WHERE s.id = :id")
    Optional<Store> findByIdForUpdate(@Param("id") Long id);
}
