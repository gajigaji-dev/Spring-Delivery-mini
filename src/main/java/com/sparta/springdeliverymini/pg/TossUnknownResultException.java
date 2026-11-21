package com.sparta.springdeliverymini.pg;

// 토스가 요청을 처리했는지 알 수 없는 경우 — 연결·응답 시간 초과, 연결 끊김, 5xx, 해석할 수 없는 응답,
// 같은 멱등 키의 이전 요청이 아직 처리 중(409 IDEMPOTENT_REQUEST_PROCESSING)
// 이 예외를 받으면 "실패"로 단정하지 않음 → 조회 API로 실제 상태를 확인하거나 같은 멱등 키로 다시 요청
// (승인이 이미 됐는데 실패로 보고 결제창을 다시 열게 하면 이중 결제가 될 수 있음)
public class TossUnknownResultException extends RuntimeException {

    public TossUnknownResultException(String message) {
        super(message);
    }
}
