package com.sparta.springdeliverymini.entity;

// 사장님 전환 신청 상태 — REQUESTED에서 APPROVED 또는 REJECTED로 한 번만 바뀜 (V17)
public enum OwnerApplicationStatus {
    REQUESTED, // 승인 대기
    APPROVED,  // 승인 — 신청자 역할이 OWNER로 바뀜
    REJECTED   // 거절 — 사유 기록, 다시 신청 가능
}
