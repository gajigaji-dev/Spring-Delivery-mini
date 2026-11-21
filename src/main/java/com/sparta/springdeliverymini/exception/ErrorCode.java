package com.sparta.springdeliverymini.exception;

// 클라이언트가 오류마다 다르게 대응해야 할 때 쓰는 구분 값 — ApiErrorResponse.code
// 문구(message)는 바뀔 수 있으니 분기는 code로만. code가 없는(null) 오류는 상태 코드·message로 처리
// 전체 표·화면 문구는 docs/api.md "오류 코드" 절, 프론트 매핑은 frontend/src/api/errorMessages.ts
//
// 401 대응 (docs/api.md 인증 장)
// - TOKEN_EXPIRED            : Access Token 만료 → POST /api/users/refresh로 재발급 후 재시도
// - TOKEN_INVALID            : Access Token 서명·형식 오류(위조 등) → 재발급하지 말고 다시 로그인
// - REFRESH_TOKEN_INVALID    : Refresh Token 없음·알 수 없음 → 다시 로그인
// - REFRESH_TOKEN_EXPIRED    : Refresh Token 만료 → 다시 로그인
// - REFRESH_TOKEN_REVOKED    : 로그아웃 등으로 폐기된 Refresh Token → 다시 로그인
// - REFRESH_TOKEN_REUSED     : 이미 재발급에 쓴 Refresh Token이 다시 옴(탈취 의심) → 계열 전체 폐기됨, 다시 로그인
//
// 주문·결제 (#77) — 상태 코드는 원인별로 고정: 입력 400 / 없음 404 / 현재 상태와 충돌·중복 요청 409 / 같은 키 다른 요청 422 / 일시 장애 503
public enum ErrorCode {
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    REFRESH_TOKEN_INVALID,
    REFRESH_TOKEN_EXPIRED,
    REFRESH_TOKEN_REVOKED,
    REFRESH_TOKEN_REUSED,

    // 400 — 다시 입력하면 해결
    INVALID_ADDRESS,             // 우편번호·도로명·상세 주소 형식 위반 (DeliveryAddress)
    INVALID_DELIVERY_REQUEST,    // 배달 요청사항 100자 초과·제어 문자 (Order)
    DUPLICATE_ORDER_ITEM,        // 같은 메뉴가 여러 항목으로 나뉨 → 수량으로 합쳐서 요청
    MIXED_STORE_ORDER,           // 한 주문에 여러 매장 메뉴
    ORDER_AMOUNT_TOO_LARGE,      // 총액이 long 범위를 넘음
    UNSUPPORTED_PAYMENT_METHOD,  // CARD 외 결제 수단
    INVALID_IDEMPOTENCY_KEY,     // Idempotency-Key 형식 오류

    // 404
    ORDER_NOT_FOUND,
    MENU_NOT_FOUND,              // 없거나 삭제된 메뉴 (장바구니에 담은 뒤 삭제된 경우)
    PAYMENT_NOT_FOUND,

    // 409 — 현재 상태와 맞지 않음 (주문을 다시 불러와 최신 상태를 보여주면 됨)
    ORDER_ALREADY_PAID,          // 결제완료 이후 주문에 다시 결제 (중복 결제 요청 포함)
    ORDER_ALREADY_CANCELLED,     // 취소된 주문에 결제·취소
    ORDER_NOT_CANCELLABLE,       // 주문수락 이후 취소
    INVALID_ORDER_STATUS,        // 그 밖의 허용되지 않는 주문 상태 변경 (사장님 수락·배달완료)
    PAYMENT_ALREADY_CANCELLED,   // 취소된 결제에 취소·환불
    PAYMENT_ALREADY_REFUNDED,    // 환불된 결제에 취소·환불
    PAYMENT_NOT_REFUNDABLE,      // 배달 전 결제 환불 → 주문 취소를 이용
    INVALID_PAYMENT_STATUS,      // 그 밖의 결제 상태 규칙 위반
    PAYMENT_IN_PROGRESS,         // 같은 Idempotency-Key 결제가 처리 중 → 잠시 후 같은 키로 재시도

    // 422
    IDEMPOTENCY_KEY_REUSED,      // 같은 Idempotency-Key로 다른 결제 요청 → 새 키로 요청

    // 503 — 요청은 반영되지 않음, 같은 Idempotency-Key로 재시도
    PAYMENT_UNAVAILABLE          // 멱등성 저장소(Redis) 장애
}
