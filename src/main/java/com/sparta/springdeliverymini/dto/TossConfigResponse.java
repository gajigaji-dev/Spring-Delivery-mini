package com.sparta.springdeliverymini.dto;

// 토스 결제 사용 여부 — 화면이 토스 결제 버튼과 모의 결제 버튼 중 무엇을 보여 줄지 정함
// clientKey는 브라우저에서 결제창을 여는 공개 키(test_ck_) — 시크릿 키는 절대 내려보내지 않음
// 키를 서버 설정(.env) 한 곳에서만 관리하려고 프론트 빌드 변수 대신 이 API로 전달
// mode: REAL(토스 테스트 결제창) / FAKE(가짜 결제창 — 토스에 접속하지 않음, V21) — 화면이 어느 결제창을 열지 정함
public record TossConfigResponse(boolean enabled, String clientKey, String mode) {
}
