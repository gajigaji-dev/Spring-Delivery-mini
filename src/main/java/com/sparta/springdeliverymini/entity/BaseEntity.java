package com.sparta.springdeliverymini.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// 생성·수정 시각을 공통으로 관리하는 부모 클래스
// @MappedSuperclass: 테이블로 만들어지지 않고, 상속한 엔티티 테이블에 컬럼(created_at, updated_at)만 추가됨
// @EntityListeners(AuditingEntityListener): 저장·수정 시점에 아래 시각을 자동으로 채움
//   (SpringDeliveryMiniApplication의 @EnableJpaAuditing이 있어야 동작)
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    // 처음 저장(INSERT)될 때 한 번만 기록
    // updatable = false: 이후 UPDATE 쿼리에서 이 컬럼은 제외 → 생성 시각이 바뀌지 않음
    // nullable = false를 두지 않은 이유: 이미 데이터가 있는 테이블에 ddl-auto: update로
    // NOT NULL 컬럼을 추가하면 기존 행에 값이 없어 ALTER TABLE이 실패하기 때문 (값은 Auditing이 항상 채움)
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // 처음 저장될 때 기록되고, 이후 엔티티 값이 바뀌어 UPDATE될 때마다 갱신
    @LastModifiedDate
    private LocalDateTime updatedAt;
}
