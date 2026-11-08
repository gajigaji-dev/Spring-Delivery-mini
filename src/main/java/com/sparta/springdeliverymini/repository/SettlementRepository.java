package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    // 해당 매장·날짜에 이미 정산이 있는지 (중복 정산 방지 1차 확인, 최종 보장은 DB UNIQUE)
    boolean existsByStoreIdAndSettlementDate(Long storeId, LocalDate settlementDate);

    Optional<Settlement> findByStoreIdAndSettlementDate(Long storeId, LocalDate settlementDate);

    // 관리자 목록 조회 — 응답에 매장 이름을 담으므로 매장을 함께 조회(N+1 방지)
    @EntityGraph(attributePaths = "store")
    List<Settlement> findAllBySettlementDateOrderById(LocalDate settlementDate);

    @EntityGraph(attributePaths = "store")
    List<Settlement> findAllBySettlementDateAndStatusOrderById(LocalDate settlementDate, SettlementStatus status);

    // 상태 변경(확정·지급)용 조회 — 행 잠금(SELECT ... FOR UPDATE)
    // 같은 정산을 동시에 확정·지급하면 뒤 요청은 앞 요청이 커밋될 때까지 기다린 뒤 바뀐 상태를 읽음
    // → 상태 검사를 둘 다 통과해 두 번 지급되거나 지급 시각이 덮어써지는 일을 막음
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // (매장은 함께 조회하지 않음 — JOIN하면 stores 행까지 잠기므로)
    @Query("SELECT s FROM Settlement s WHERE s.id = :id")
    Optional<Settlement> findByIdForUpdate(@Param("id") Long id);
}
