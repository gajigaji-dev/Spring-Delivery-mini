package com.sparta.springdeliverymini.exception;

// 같은 상태 코드 안에서 클라이언트가 다르게 대응해야 하는 오류의 구분 값 — ApiErrorResponse.code
// 대부분의 오류는 상태 코드·message로 충분하므로 code가 없음(null). 문구(message)는 바뀔 수 있으니 분기는 code로만
//
// 401 대응 (docs/api.md 인증 장)
// - TOKEN_EXPIRED            : Access Token 만료 → POST /api/users/refresh로 재발급 후 재시도
// - TOKEN_INVALID            : Access Token 서명·형식 오류(위조 등) → 재발급하지 말고 다시 로그인
// - REFRESH_TOKEN_INVALID    : Refresh Token 없음·알 수 없음 → 다시 로그인
// - REFRESH_TOKEN_EXPIRED    : Refresh Token 만료 → 다시 로그인
// - REFRESH_TOKEN_REVOKED    : 로그아웃 등으로 폐기된 Refresh Token → 다시 로그인
// - REFRESH_TOKEN_REUSED     : 이미 재발급에 쓴 Refresh Token이 다시 옴(탈취 의심) → 계열 전체 폐기됨, 다시 로그인
public enum ErrorCode {
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    REFRESH_TOKEN_INVALID,
    REFRESH_TOKEN_EXPIRED,
    REFRESH_TOKEN_REVOKED,
    REFRESH_TOKEN_REUSED
}
