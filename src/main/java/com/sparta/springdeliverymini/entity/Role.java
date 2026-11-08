package com.sparta.springdeliverymini.entity;

public enum Role {
    CUSTOMER, // 고객
    OWNER,    // 사장님
    ADMIN     // 관리자 — 정산 확정·지급 처리. 회원가입으로는 만들 수 없음 (UserService), DB에서 직접 지정
}
