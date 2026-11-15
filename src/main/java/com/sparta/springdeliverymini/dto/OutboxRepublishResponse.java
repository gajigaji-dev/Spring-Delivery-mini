package com.sparta.springdeliverymini.dto;

// 관리자 FAILED 이벤트 일괄 재발행 결과 — republished: 이번 요청으로 PENDING으로 되돌린 건수
public record OutboxRepublishResponse(int republished) {
}
