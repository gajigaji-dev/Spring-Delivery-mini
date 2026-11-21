package com.sparta.springdeliverymini.entity;

// 결제대행(PG) 공급자 (V20) — 지금은 토스페이먼츠 테스트 모드만
// DB CHECK 제약(ck_payments_pg_provider·ck_pg_cancel_requests_provider)과 같은 값만 둠
public enum PgProvider {
    TOSS
}
