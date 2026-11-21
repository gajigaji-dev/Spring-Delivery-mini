package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.OwnerApplication;
import com.sparta.springdeliverymini.entity.OwnerApplicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OwnerApplicationRepository extends JpaRepository<OwnerApplication, Long> {

    // 대기 중 신청 여부 — 중복 신청에 구체적인 메시지를 주기 위한 사전 검사 (최종 근거는 부분 유니크 인덱스)
    boolean existsByUserIdAndStatus(Long userId, OwnerApplicationStatus status);

    // 내 최근 신청 1건 — 인덱스 idx_owner_applications_user_id_id
    Optional<OwnerApplication> findFirstByUserIdOrderByIdDesc(Long userId);

    // 관리자 목록 — 응답에 신청자·처리자 아이디가 들어가므로 함께 조회 (행마다 회원을 따로 읽지 않음)
    // 인덱스 idx_owner_applications_status_id, 정렬·크기는 PageQuery.toPageable()
    @EntityGraph(attributePaths = {"user", "decidedBy"})
    Slice<OwnerApplication> findAllByStatus(OwnerApplicationStatus status, Pageable pageable);

    // 승인·거절용 조회 — 행 잠금. 같은 신청에 승인·거절이 동시에 와도 뒤 요청은 바뀐 상태를 읽고 409
    // (신청자 users 행은 JOIN하지 않음 — 함께 잠그지 않도록, 역할 변경은 dirty checking으로 UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM OwnerApplication a WHERE a.id = :id")
    Optional<OwnerApplication> findByIdForUpdate(@Param("id") Long id);
}
