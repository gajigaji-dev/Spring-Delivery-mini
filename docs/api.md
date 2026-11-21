# API 명세

[← README](../README.md)


## 공통 사항
- 인증이 필요한 API는 요청 헤더에 토큰을 담아 보냄
  ```
  Authorization: Bearer {accessToken}
  ```
- 에러 응답은 모두 같은 형식 (`exception.ApiErrorResponse`, Security의 401/403과 Spring MVC 표준 404/405 포함)
  ```json
  {
    "status": 409,
    "error": "Conflict",
    "code": null,
    "message": "에러 메시지",
    "path": "/api/admin/payments/1/refund",
    "traceId": "5af890963e74404eb8ac559bfc252067"
  }
  ```
  - `message`는 기존과 같은 필드·문구 (기존 클라이언트는 그대로 동작), 나머지 필드는 #38에서 추가
  - `code`(V15에서 추가): 같은 상태 코드 안에서 클라이언트가 대응을 나눠야 하는 오류에만 값이 있고 그 밖에는 `null`. 분기는 `message` 문구가 아니라 `code`로 ([인증](#인증-토큰-access--refresh), 주문·결제 오류 코드(아래 표))
  - 500은 내부 예외 메시지를 숨기고 공통 문구로 응답 → `traceId`로 서버 로그 확인
  - 503(모든 API 공통): DB가 제때 응답하지 못함 — 행 잠금 대기 초과(5초)·문장 실행 초과(30초)·커넥션 부족. 요청은 반영되지 않았으므로 잠시 후 다시 시도 ([운영 프로필 6장](operations.md))
- 추적 헤더 `X-Trace-Id`
  - 모든 응답에 포함. 요청에 `X-Trace-Id`(영문·숫자·하이픈 8~64자)를 보내면 그 값을 그대로 사용, 없거나 형식이 다르면 서버가 생성
  - 같은 값이 에러 응답 `traceId`, 서버 로그 각 줄의 `[traceId]`에 들어감
- CORS (#61): 브라우저에서 호출할 수 있는 출처는 서버 설정 `CORS_ALLOWED_ORIGINS`로 정함 (local·dev 기본 `http://localhost:5173`·`http://localhost:3000`). 자격 증명(쿠키) 허용(V15) — Refresh Token 쿠키 때문이며, API 인증은 여전히 `Authorization` 헤더로만
  - 허용 요청 헤더: `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Trace-Id` / JS에서 읽을 수 있는 응답 헤더: `X-Has-Next`, `X-Next-After-Id`, `X-Page`, `X-Size`, `X-Trace-Id`, `Idempotent-Replayed`
  - 허용하지 않은 출처는 403 (`Invalid CORS request`, 공통 오류 형식 아님) — 설정 근거는 [운영 프로필 7장](operations.md)
- API 문서(Swagger UI): local·dev 프로필에서 `/swagger-ui.html`, 명세 `/v3/api-docs` (기본 설정·prod 프로필은 비활성 → 404, [운영 설정](operations.md))
  - Authorize 버튼에 로그인 응답의 `accessToken`을 넣으면 Bearer 헤더가 자동으로 붙음
- 목록 페이지네이션 (#40) — 매장·메뉴·주문·매장별 주문 목록 공통
  - 요청: `?page=0&size=20` (`page` 0부터, `size` 기본 20 · 최대 100). 범위를 벗어나거나 숫자가 아니면 400
  - 응답 본문은 JSON 배열 그대로이고, 페이지 정보는 응답 헤더로 전달

    | 헤더 | 내용 |
    |---|---|
    | `X-Page` | 현재 페이지 번호 |
    | `X-Size` | 요청한 페이지 크기 |
    | `X-Has-Next` | 다음 페이지가 있으면 `true` (전체 개수는 제공하지 않음) |
  - 정렬은 id 오름차순(등록순) 고정. 범위를 넘은 페이지는 빈 배열 `[]`
  - 파라미터 없이 호출하면 첫 20건만 응답 (이전에는 전체) — 배경·측정 결과는 [성능 개선](performance.md)
- keyset 페이징 (#54) — 주문 목록(`GET /api/orders`)만 추가 지원, `page` 방식도 그대로 사용 가능
  - 요청: `?afterId={마지막으로 받은 주문 id}&size=20` (`afterId` 0 이상, 0이면 처음부터). `page`와 함께 보내면 400
  - 응답 헤더: `X-Size`, `X-Has-Next`, `X-Next-After-Id`(다음 페이지가 있을 때만, 다음 요청의 `afterId` 값). 페이지 번호가 없으므로 `X-Page`는 보내지 않음
  - `page` 방식 응답에도 `X-Next-After-Id`가 붙음 → 첫 페이지를 파라미터 없이 받고 이어서 keyset으로 넘길 수 있음
  - 뒤쪽 페이지도 응답 시간이 일정하고, 넘기는 중 새 주문이 들어와도 이미 받은 주문이 다시 나오지 않음 — 측정 결과는 [성능 개선](performance.md) 9장
- 공통 에러 코드

  | 코드 | 상황 |
  |---|---|
  | 400 | 요청 값 검증 실패, JSON 형식 오류, 필수 쿼리 파라미터 누락, 경로·쿼리 파라미터 형식 오류 |
  | 401 | 토큰이 없거나 유효하지 않음(만료 `TOKEN_EXPIRED`·위조 `TOKEN_INVALID`), 로그인 실패, Refresh Token 거부(`REFRESH_TOKEN_*`) |
  | 403 | 권한 없음 (역할이 다르거나 본인 데이터가 아님) |
  | 404 | 대상(매장·메뉴·주문·정산)이 없음 |
  | 409 | 중복(아이디, 결제) 또는 현재 상태에서 할 수 없는 요청 (주문·정산 상태 전이) |
  | 405 | 지원하지 않는 HTTP 메서드 (`Allow` 헤더 포함) |
  | 500 | 예상하지 못한 서버 오류 (`traceId`로 로그 확인) |

- 주문·결제 오류 코드 (#77)
  - 원칙: 상태 코드는 원인별로 고정(입력 400 / 없음 404 / 현재 상태와 충돌·중복 요청 409 / 같은 키 다른 요청 422 / 일시 장애 503), 같은 상태 코드 안의 구분은 `code`
  - 화면 문구는 `code`로 정함(`frontend/src/api/errorMessages.ts`), 매핑 없는 `code`·`code` 없는 오류는 서버 `message`
  - 401·403(권한)은 화면 대응이 같아 `code`를 붙이지 않음(인증 `TOKEN_*`·`REFRESH_TOKEN_*` 제외)

  | 상태 | code | 상황 | 클라이언트 대응 |
  |---|---|---|---|
  | 400 | `INVALID_ADDRESS` | 우편번호 5자리 아님, 도로명 없음·150자 초과, 상세 100자 초과, 제어 문자 | 주소 칸 안내 |
  | 400 | `INVALID_DELIVERY_REQUEST` | 배달 요청사항 100자 초과·제어 문자 (V19) | 요청사항 칸 안내 |
  | 400 | `DUPLICATE_ORDER_ITEM` | 같은 메뉴가 여러 항목 | 장바구니 확인 |
  | 400 | `MIXED_STORE_ORDER` | 여러 매장 메뉴가 섞임 | 장바구니 확인 |
  | 400 | `ORDER_AMOUNT_TOO_LARGE` | 총액이 long 범위 초과 | 수량 줄이기 |
  | 400 | `UNSUPPORTED_PAYMENT_METHOD` | CARD 외 결제 수단 | 카드 결제 |
  | 400 | `INVALID_IDEMPOTENCY_KEY` | `Idempotency-Key` 형식 오류 | 새로고침 |
  | 400 | `PAYMENT_AMOUNT_MISMATCH` / `INVALID_PG_ORDER_ID` | 토스 승인 요청의 금액·주문번호가 주문과 다름 — 승인하지 않음 (V20) | 주문에서 다시 결제 |
  | 400 | `PG_PAYMENT_REJECTED` | 토스가 승인 거절(카드 거절·결제 시간 만료) | 결제창 다시 |
  | 400 | `INVALID_REFUND_AMOUNT` | 부분 환불 금액이 남은 결제 금액을 넘음 (V21) | 금액 확인 |
  | 404 | `ORDER_NOT_FOUND` / `MENU_NOT_FOUND` / `PAYMENT_NOT_FOUND` | 대상 없음 (메뉴는 삭제 포함) | 목록으로 |
  | 409 | `ORDER_ALREADY_PAID` | 결제완료 이후 주문에 결제 (중복 결제 요청, 다른 키로 이미 결제됨 포함) | 주문 다시 불러오기, 저장한 결제 키 버림 |
  | 409 | `ORDER_ALREADY_CANCELLED` | 취소된 주문에 결제·취소 | 주문 다시 불러오기 |
  | 409 | `ORDER_NOT_CANCELLABLE` | 주문수락 이후 취소 | 주문 다시 불러오기, 매장 문의 |
  | 409 | `INVALID_ORDER_STATUS` | 그 밖의 허용되지 않는 주문 상태 변경 (사장님 수락·배달완료) | 주문 다시 불러오기 |
  | 409 | `PAYMENT_ALREADY_CANCELLED` / `PAYMENT_ALREADY_REFUNDED` | 끝난 결제에 취소·환불 | 다시 불러오기 |
  | 409 | `PAYMENT_NOT_REFUNDABLE` | 배달 전 결제 환불 | 주문 취소 이용 |
  | 409 | `INVALID_PAYMENT_STATUS` | 그 밖의 결제 상태 규칙 위반 | 다시 불러오기 |
  | 409 | `PAYMENT_IN_PROGRESS` | 같은 `Idempotency-Key` 결제가 처리 중 | 잠시 후 **같은 키로** 재시도 (키를 버리지 않음) |
  | 422 | `IDEMPOTENCY_KEY_REUSED` | 같은 `Idempotency-Key`로 다른 결제 요청 | 새 키 |
  | 503 | `PAYMENT_UNAVAILABLE` | 멱등성 저장소(Redis) 장애 — 결제하지 않음 | 잠시 후 같은 키로 재시도 |
  | 503 | `PG_NOT_CONFIGURED` | 토스 키 없음·시크릿 키 거부 — 결제하지 않음 (V20) | 모의 결제 또는 나중에 |
  | 503 | `PG_RESULT_UNKNOWN` | 토스 승인 결과를 확인하지 못함 (V20) | **결제창을 다시 열지 말고** 같은 승인 요청 재시도 |

  소셜 로그인 코드(`SOCIAL_*`·`OAUTH2_*`, V22)는 [소셜 로그인](#소셜-로그인-v22-카카오구글) 절

- 운영 엔드포인트 (Actuator)

  | 경로 | 노출 | 권한 |
  |---|---|---|
  | `GET /actuator/health` | 모든 프로필 (UP/DOWN만, 상세 숨김) | 공개 |
  | `GET /actuator/prometheus` | local(8080) · dev(관리 포트 8081, 호스트 비공개) | 공개 (수집기용) |
  | 그 밖의 `/actuator/**` (metrics 등) | local만 metrics·info | ADMIN |

## API 목록

| 기능 | Method | URL | 인증 | 권한 | 성공 |
|---|---|---|---|---|---|
| 회원가입 | POST | `/api/users/signup` | X | - | 201 |
| 로그인 | POST | `/api/users/login` | X | - | 200 |
| Access Token 재발급 | POST | `/api/users/refresh` | 쿠키 | Refresh Token 쿠키 | 200 |
| 로그아웃 | POST | `/api/users/logout` | 쿠키 | Refresh Token 쿠키 (없어도 204) | 204 |
| 내 정보 조회 | GET | `/api/users/me` | O | 로그인 사용자 | 200 |
| 소셜 로그인 시작 (브라우저 이동) | GET | `/oauth2/authorization/{provider}[?mode=link]` | X | - | 302 |
| 소셜 로그인 콜백 (공급자 → 서버) | GET | `/login/oauth2/code/{provider}` | X | 시작 기록 쿠키 | 302 |
| 사용 가능한 소셜 공급자 | GET | `/api/users/social/providers` | X | - | 200 |
| 내 소셜 계정 | GET | `/api/users/me/social-accounts` | O | 로그인 사용자 | 200 |
| 소셜 계정 연결 시작 | POST | `/api/users/me/social-accounts/{provider}/link` | O | CUSTOMER·OWNER | 204 |
| 소셜 계정 연결 해제 | DELETE | `/api/users/me/social-accounts/{provider}` | O | 로그인 사용자 | 204 |
| 사장님 전환 신청 | POST | `/api/owner-applications` | O | CUSTOMER | 201 |
| 내 사장님 전환 신청 | GET | `/api/owner-applications/me` | O | 로그인 사용자 | 200 |
| 매장 생성 | POST | `/api/stores` | O | OWNER | 201 |
| 매장 목록 조회 | GET | `/api/stores?page=&size=` | X | - | 200 |
| 매장 단건 조회 | GET | `/api/stores/{id}` | X | - | 200 |
| 매장별 주문 조회 | GET | `/api/stores/{id}/orders?page=&size=` | O | OWNER (본인 매장) | 200 |
| 매장별 정산 조회 | GET | `/api/stores/{id}/settlements?from=&to=` | O | OWNER (본인 매장) | 200 |
| 매장별 일 정산 기록 목록 | GET | `/api/stores/{id}/daily-settlements?from=&to=&status=` | O | OWNER (본인 매장) | 200 |
| 매장별 일 정산 기록 상세 | GET | `/api/stores/{id}/daily-settlements/{settlementId}` | O | OWNER (본인 매장) | 200 |
| 내 정산 내역 (전체 매장) | GET | `/api/settlements?storeId=&status=&from=&to=&page=&size=` | O | OWNER (본인 매장) | 200 |
| 메뉴 등록 | POST | `/api/menus` | O | OWNER (본인 매장) | 201 |
| 메뉴 목록 조회 | GET | `/api/menus?page=&size=` | X | - | 200 |
| 메뉴 단건 조회 | GET | `/api/menus/{id}` | X | - | 200 |
| 메뉴 수정 | PUT | `/api/menus/{id}` | O | OWNER (본인 매장 메뉴) | 200 |
| 메뉴 삭제 | DELETE | `/api/menus/{id}` | O | OWNER (본인 매장 메뉴) | 204 |
| 주문 생성 | POST | `/api/orders` | O | CUSTOMER | 201 |
| 주문 목록 조회 | GET | `/api/orders?page=&size=` / `?afterId=&size=` | O | CUSTOMER / OWNER | 200 |
| 주문 단건 조회 (결제 정보 포함) | GET | `/api/orders/{id}` | O | CUSTOMER (본인 주문) / OWNER (본인 매장 주문) | 200 |
| 주문 취소 (결제 취소 포함) | PATCH | `/api/orders/{id}/cancel` | O | CUSTOMER (본인 주문) | 204 |
| 주문 상태 변경 | PATCH | `/api/orders/{id}/status` | O | OWNER (본인 매장 주문) | 204 |
| 결제 | POST | `/api/payments` | O | CUSTOMER (본인 주문) | 201 |
| 내 결제 목록 조회 | GET | `/api/payments?page=&size=` | O | CUSTOMER (본인 결제) | 200 |
| 결제 단건 조회 | GET | `/api/payments/{id}` | O | CUSTOMER (본인 결제) | 200 |
| 토스 결제 사용 여부 (V20) | GET | `/api/payments/toss/config` | O | CUSTOMER | 200 |
| 토스 결제창 값 (V20) | POST | `/api/payments/toss/checkout` | O | CUSTOMER (본인 주문) | 200 |
| 토스 결제 승인 (V20) | POST | `/api/payments/toss/confirm` | O | CUSTOMER (본인 주문) | 201 |
| 내 알림 목록 | GET | `/api/notifications?page=&size=` | O | 로그인 사용자 | 200 |
| 안 읽은 알림 개수 | GET | `/api/notifications/unread-count` | O | 로그인 사용자 | 200 |
| 알림 읽음 처리 | PATCH | `/api/notifications/{id}/read` | O | 로그인 사용자 (본인 알림) | 204 |
| 사장님 실시간 주문 알림 (SSE) | GET | `/api/owner/order-events?storeId=` | O | OWNER (본인 매장) | 200 `text/event-stream` |
| 정산 목록 조회 (관리자) | GET | `/api/admin/settlements?date=&status=` | O | ADMIN | 200 |
| 정산 확정 (관리자) | PATCH | `/api/admin/settlements/{id}/confirm` | O | ADMIN | 200 |
| 정산 지급 (관리자) | PATCH | `/api/admin/settlements/{id}/pay` | O | ADMIN | 200 |
| 정산 상태 변경 이력 (관리자) | GET | `/api/admin/settlements/{id}/histories` | O | ADMIN | 200 |
| 정산 재집계 (관리자) | POST | `/api/admin/settlements/reaggregate?date=&storeId=` | O | ADMIN | 200 |
| 정산 집계 실패 기록 (관리자) | GET | `/api/admin/settlements/aggregation-failures?status=` | O | ADMIN | 200 |
| 결제 환불 (관리자) | PATCH | `/api/admin/payments/{id}/refund` | O | ADMIN | 200 |
| 결제 부분 환불 (관리자, V21) | POST | `/api/admin/payments/{id}/refunds` | O | ADMIN | 201 |
| Outbox 이벤트 목록 (관리자) | GET | `/api/admin/outbox-events?status=&page=&size=` | O | ADMIN | 200 |
| Outbox 이벤트 재발행 (관리자) | POST | `/api/admin/outbox-events/{id}/republish` | O | ADMIN | 200 |
| Outbox FAILED 일괄 재발행 (관리자) | POST | `/api/admin/outbox-events/republish?limit=` | O | ADMIN | 200 |
| 사장님 전환 신청 목록 (관리자) | GET | `/api/admin/owner-applications?status=&page=&size=` | O | ADMIN | 200 |
| 사장님 전환 승인 (관리자) | PATCH | `/api/admin/owner-applications/{id}/approve` | O | ADMIN | 200 |
| 사장님 전환 거절 (관리자) | PATCH | `/api/admin/owner-applications/{id}/reject` | O | ADMIN | 200 |
| 매장 수수료율 설정 (관리자) | PUT | `/api/admin/stores/{id}/fee-rate` | O | ADMIN | 200 |
| 매장 수수료율 해제 (관리자) | DELETE | `/api/admin/stores/{id}/fee-rate` | O | ADMIN | 200 |

## 주문 상태 흐름

```
ORDER_REQUEST(주문요청) ──결제──▶ PAYMENT_COMPLETED(결제완료) ──OWNER 수락──▶ ORDER_ACCEPTED(주문수락) ──OWNER──▶ DELIVERY_COMPLETED(배달완료)
       │                                   │                                                                     │
       └──CUSTOMER 취소──┬─────────────────┘                                                       ADMIN 환불 (결제만 REFUNDED,
                         ▼                                                                          주문 상태는 그대로)
                 ORDER_CANCELLED(주문취소) — 결제완료였다면 결제도 CANCELLED
```

| 전이 | `Order` 메서드 | 호출 위치 |
|---|---|---|
| `ORDER_REQUEST / PAYMENT_COMPLETED → ORDER_CANCELLED` | `cancel()` (결제완료면 `Payment.cancel()`도 함께) | `OrderService.cancelOrder` |
| `ORDER_REQUEST → PAYMENT_COMPLETED` | `pay()` | `PaymentService.pay` |
| `PAYMENT_COMPLETED → ORDER_ACCEPTED` | `accept()` | `OrderService.statusOrder` |
| `ORDER_ACCEPTED → DELIVERY_COMPLETED` | `complete(deliveredAt)` (배달완료 시각 기록) | `OrderService.statusOrder` |

- 상태를 임의 값으로 바꾸는 메서드는 없음 → 어디서 호출하든 위 표 외의 전이는 `InvalidOrderStatusException`(409)

---

## 회원

### 회원가입 `POST /api/users/signup`
Request
```json
{ "username": "user1", "password": "password123" }
```
- `username`: 4~20자
- `password`: 8~72자 (BCrypt로 암호화해 저장)
- `role`: 선택, 생략하거나 `CUSTOMER`만 (V17 승인제). **가입 회원은 항상 CUSTOMER**
  - `OWNER` → 400: 사장님은 가입 후 [사장님 전환 신청](#사장님-전환-신청-v17) → 관리자 승인 (이전에는 `role: OWNER`만으로 확인 없이 매장·메뉴를 만들 수 있었음)
  - `ADMIN` → 400: 관리자는 DB에서 직접 지정

Response `201 Created`
```json
{ "id": 1, "username": "user1", "role": "CUSTOMER" }
```

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패, role이 CUSTOMER가 아님 (`OWNER`·`ADMIN`) |
| 409 | 이미 사용 중인 아이디 |

### 로그인 `POST /api/users/login`
Request
```json
{ "username": "user1", "password": "password123" }
```
Response `200 OK`
```
Set-Cookie: refresh_token=...; Path=/api/users; Max-Age=1209600; Secure; HttpOnly; SameSite=Strict
```
```json
{ "accessToken": "eyJhbGciOi...", "tokenType": "Bearer", "expiresIn": 900 }
```
- Access Token 유효 시간: 15분 (`expiresIn`, 초 — V15에서 추가. V16에서 1시간 → 15분)
- Refresh Token(V15)은 본문에 없고 HttpOnly 쿠키로만 전달 (14일, JS에서 읽을 수 없음). 로그인할 때마다 별도의 로그인(계열)이 생김 → 기기별 로그아웃이 서로 영향 없음
- 브라우저는 `fetch(..., { credentials: 'include' })`로 호출해야 쿠키가 저장됨 ([인증](#인증-토큰-access--refresh))

| 코드 | 상황 |
|---|---|
| 400 | 아이디/비밀번호 누락 |
| 401 | 아이디 또는 비밀번호 불일치 (어느 쪽이 틀렸는지 구분하지 않음) |

### Access Token 재발급 `POST /api/users/refresh`
Access Token 없이 호출. 요청 본문 없음 — `refresh_token` 쿠키로 인증 (브라우저는 `credentials: 'include'`)

Response `200 OK` — 로그인 응답과 같은 형식 + 새 `refresh_token` 쿠키
- 쓴 Refresh Token은 즉시 폐기되고 새 토큰으로 교체(rotation). 새 토큰의 수명은 재발급 시점부터 다시 14일, 단 로그인 후 30일(절대 만료, V16)을 넘지 않음 → 계속 써도 30일마다 재로그인
- 새 Access Token의 `role`은 DB의 현재 역할 (로그인 뒤 역할이 바뀌었으면 반영)

| 코드 | `code` | 상황 | 클라이언트 대응 |
|---|---|---|---|
| 401 | `REFRESH_TOKEN_INVALID` | 쿠키 없음, 모르는 값(만료 후 7일이 지나 정리된 토큰 포함) | 로그인 화면 |
| 401 | `REFRESH_TOKEN_EXPIRED` | 14일 동안 재발급하지 않음, 또는 로그인 후 30일 지남 | 로그인 화면 |
| 401 | `REFRESH_TOKEN_REVOKED` | 로그아웃·재사용 감지로 폐기된 토큰 | 로그인 화면 |
| 401 | `REFRESH_TOKEN_REUSED` | 이미 재발급에 쓴 토큰이 다시 옴(탈취 의심) → **같은 로그인의 토큰 전체 폐기** | 로그인 화면 |
| 403 | - | 허용하지 않은 출처(Origin)에서 호출 (CORS) | - |
- 401이면 `Set-Cookie: refresh_token=; Max-Age=0`으로 브라우저 쿠키도 삭제

### 로그아웃 `POST /api/users/logout`
Access Token 없이 호출 가능 (Access가 만료된 상태에서도 로그아웃할 수 있도록). 요청 본문 없음

Response `204 No Content` + `Set-Cookie: refresh_token=; Max-Age=0`
- 이 쿠키가 속한 로그인(계열)의 Refresh Token을 모두 폐기. 다른 기기의 로그인은 유지
- 멱등: 쿠키가 없거나 이미 로그아웃했어도 204
- 이미 발급된 Access Token은 만료(최대 15분)까지 서버에서 거부하지 않음 → 클라이언트가 메모리에서 지움 (수명을 짧게 둔 이유, [프로젝트 구조 · 인증](architecture.md#인증-access--refresh-token-v15))

### 인증 토큰 (Access · Refresh)
| | Access Token | Refresh Token |
|---|---|---|
| 형식 | JWT (HS256, `sub`=아이디, `role`) | 무작위 256비트 (Base64URL 43자), DB에는 SHA-256 해시만 |
| 수명 | 15분 | 14일 (재발급마다 다시 14일, 단 로그인 후 30일을 넘지 않음) |
| 전달 | 응답 본문 `accessToken` → 요청 헤더 `Authorization: Bearer` | HttpOnly 쿠키 `refresh_token` (`Path=/api/users`, `SameSite=Strict`, `Secure`) |
| 프론트 보관 | **메모리(JS 변수)** — localStorage에 두지 않음 (XSS로 읽힘) | 브라우저가 관리 (JS 접근 불가) |

401 응답의 `code`로 대응을 나눔 (메시지 문구로 분기하지 않음)

| `code` | 상황 | 대응 |
|---|---|---|
| `TOKEN_EXPIRED` | Access Token 만료 | `POST /api/users/refresh` 후 원래 요청 재시도 |
| `TOKEN_INVALID` | 서명 불일치(위조)·형식 오류 — 만료됐더라도 서명이 틀리면 이쪽 | 재발급하지 말고 로그인 화면 |
| `null` | `Authorization` 헤더 없음 | 로그인 화면 (새로고침 직후라면 먼저 재발급 시도) |
| `REFRESH_TOKEN_*` | 재발급 실패 | 로그인 화면 |

프론트엔드 연동 규칙
- 새로고침하면 메모리의 Access Token이 사라지므로 앱 시작 시 `POST /api/users/refresh`를 한 번 호출해 로그인 상태를 복구
- **재발급 요청은 한 번에 하나만**: 여러 요청이 동시에 `TOKEN_EXPIRED`를 받으면 재발급 1번을 공유(single-flight)하고 결과를 기다려 재시도. 같은 Refresh Token으로 두 번 재발급하면 두 번째가 재사용(`REFRESH_TOKEN_REUSED`)으로 처리되어 로그인 전체가 끊김 (탭 여러 개도 같은 쿠키를 쓰므로 BroadcastChannel 등으로 공유하거나, 실패 시 로그인 화면)
- `login`·`refresh`·`logout`은 `credentials: 'include'`, 그 밖의 API는 쿠키가 필요 없음 (Refresh 쿠키는 `Path=/api/users`라 다른 API에 실리지 않음)
- 프론트엔드와 API를 다른 사이트(등록 도메인이 다름)에 배포하면 `SameSite=Strict` 쿠키가 전송되지 않음 → 서버 `REFRESH_TOKEN_COOKIE_SAME_SITE=None` 필요 ([운영 프로필 7장](operations.md))

### 내 정보 조회 `GET /api/users/me`
Response `200 OK` (`MyInfoResponse`, #62)
```json
{ "id": 3, "username": "owner1", "role": "OWNER", "createdAt": "2026-11-18T10:00:00", "storeIds": [2, 5] }
```
- 토큰의 아이디로 **DB의 현재 회원**을 조회해 응답 → 토큰 발급 뒤 역할이 바뀌었으면 `role`은 바뀐 값
- `storeIds`: OWNER의 매장 id (id 오름차순). CUSTOMER·ADMIN은 빈 배열 `[]` (null 아님)
- 별도 표시 이름(닉네임) 컬럼이 없어 화면 표시는 `username` 사용, 비밀번호는 포함하지 않음
- 변경 전(#62 이전) 응답 `{ "username", "authorities": ["ROLE_..."] }`는 토큰 값을 그대로 돌려주던 형식 → 제거

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음·만료·위조, 회원이 삭제됨 |

### 사장님 전환 신청 (V17)
가입은 모두 고객(CUSTOMER) → 사장님 전환 신청 → 관리자 승인 시 OWNER. 기존 OWNER 계정은 그대로 (사용자 결정 2026-11-21)

```
REQUESTED(승인 대기) ── 관리자 승인 ──▶ APPROVED (신청자 role → OWNER)
        └────────────── 관리자 거절 ──▶ REJECTED (사유 기록, 다시 신청 가능)
```

공통 응답 (`OwnerApplicationResponse`)
```json
{ "id": 3, "userId": 12, "username": "cus1", "businessName": "치킨집", "status": "APPROVED",
  "rejectReason": null, "requestedAt": "2026-11-21T10:00:00", "decidedAt": "2026-11-21T11:00:00", "decidedBy": "admin1" }
```

#### 신청 `POST /api/owner-applications` (CUSTOMER)
Request `{ "businessName": "치킨집" }` (필수, 50자 이하) → `201 Created`

| 코드 | 상황 |
|---|---|
| 400 | 상호 누락·50자 초과 |
| 403 | 고객이 아님 (OWNER·ADMIN, DB 역할 기준) |
| 409 | 이미 승인 대기 중인 신청이 있음(동시 신청도 1건만 — 부분 유니크 인덱스), **진행 중인 주문이 있음**(주문요청·결제완료·주문수락) |

- 진행 중 주문을 막는 이유: 전환 뒤에는 고객 API(취소·결제 조회)가 DB 역할 기준 403 → 그 주문을 다룰 수 없게 됨. 지난 주문·결제 기록은 DB에 남지만 전환 후 고객 화면에서는 볼 수 없음

#### 내 신청 `GET /api/owner-applications/me` (로그인 사용자)
가장 최근 신청 1건 → `200`, 없으면 `404`. 승인 후 OWNER가 돼도 결과 확인 가능

#### 관리자 목록 `GET /api/admin/owner-applications?status=REQUESTED&page=0&size=20`
`status` 기본 `REQUESTED`, id 오름차순(오래된 신청부터), 본문 배열 + `X-Page`·`X-Size`·`X-Has-Next`

#### 승인 `PATCH /api/admin/owner-applications/{id}/approve` · 거절 `PATCH .../{id}/reject`
- 승인: 요청 본문 없음. 신청자 역할을 같은 트랜잭션에서 OWNER로 변경, 승인 시점에 진행 중 주문을 다시 확인
- 거절: `{ "reason": "상호 확인 불가" }` (필수, 255자 이하, 신청자에게 그대로 보임)
- 처리자는 토큰의 인증 정보(관리자)로 기록. 신청 행을 잠가 같은 신청에 승인·거절이 동시에 와도 1건만 성공

| 코드 | 상황 |
|---|---|
| 400 | 거절 사유 누락 |
| 403 | ADMIN이 아님 |
| 404 | 신청 없음 |
| 409 | 대기 중이 아닌 신청(이미 승인·거절됨), 신청자가 고객이 아님, 진행 중인 주문이 있음(승인) |

**토큰**: 승인 뒤에도 이미 발급된 Access Token(최대 15분)은 `role=CUSTOMER` → 사장님 API 403. `POST /api/users/refresh`가 DB 역할로 새 토큰을 주므로 재발급 후부터 OWNER (프론트는 "사장님 화면으로 전환" 버튼에서 재발급 → `/me` 다시 조회)

후속 후보: 승인·거절 결과 알림(Outbox 이벤트) — 지금은 신청자가 `/me`로 확인

### 소셜 로그인 (V22, 카카오·구글)

OAuth2 Authorization Code 흐름. 구조·설계 근거는 [프로젝트 구조 · 소셜 로그인](architecture.md#소셜-로그인-v22), 로컬 확인은 [로컬 확인 9장](local-verification.md)

| 단계 | 요청 | 설명 |
|---|---|---|
| 1. 시작 | 브라우저 이동 `GET /oauth2/authorization/{kakao\|google}` | 서버가 공급자 동의 화면으로 302. 시작 기록(state·nonce)은 서명된 HttpOnly 쿠키 `oauth2_auth_request`(Path=/login/oauth2/code, SameSite=Lax, 10분). 키가 없는(꺼진) 공급자는 404 |
| 2. 콜백 | 공급자 → `GET /login/oauth2/code/{provider}?code=&state=` | 공급자 콘솔에 등록하는 Redirect URI. state 확인 → 코드 교환 → 회원 처리 |
| 3. 결과 | 서버 → 302 `{social.frontend-callback-url}?result=login` | 로그인 성공: 기존 로그인과 같은 **Refresh Token 쿠키만** 발급(토큰은 URL에 없음) → 프론트가 `POST /api/users/refresh`로 Access 받음 |
| | `?result=linked&provider=kakao` | 계정 연결 성공 (쿠키 변화 없음) |
| | `?error={code}` | 아래 표 |

- **처음 로그인**: CUSTOMER로 자동 가입. 아이디는 `{공급자}_` + 소문자·숫자 10자(예: `kakao_3k9x0a1b2c`), 비밀번호 없음 → 아이디·비밀번호 로그인은 401
- **중복 가입 없음**: `social_accounts (provider, provider_user_id)` UNIQUE. 같은 계정으로 동시에 처음 로그인해도 1명만 생기고 나머지는 그 회원으로 로그인
- **저장하는 값**: 공급자 회원 식별자(카카오 회원번호, 구글 `sub`)만. 이메일·닉네임은 요청하지도 저장하지도 않음 (카카오 동의 항목 없음, 구글 scope `openid`)
- 이후 인증은 기존과 같음 (Access 15분 + Refresh 쿠키, `/api/users/refresh`·`/logout` 그대로)

#### 사용 가능한 공급자 `GET /api/users/social/providers` (토큰 없음)
Response `200 OK` — `["google", "kakao"]` (키가 설정된 것만, 이름순. 비어 있으면 버튼 숨김)

#### 기존 회원에 연결
로그인한 상태에서만, 회원이 직접 시작 (이메일 자동 매칭 없음)
1. `POST /api/users/me/social-accounts/{provider}/link` (Bearer, **`credentials: 'include'`**) → `204` + HttpOnly 쿠키 `social_link`(서명, 회원 id·공급자, 5분, Path=/oauth2/authorization)
2. 브라우저 이동 `GET /oauth2/authorization/{provider}?mode=link` → 쿠키를 시작 기록으로 옮기고 지움 → 동의 → 콜백 → `?result=linked`
- `mode=link`인데 쿠키가 없거나 만료·변조면 새 회원을 만들지 않고 `?error=SOCIAL_LINK_EXPIRED`

| 상태 | code | 상황 |
|---|---|---|
| 403 | `SOCIAL_LINK_FORBIDDEN` | 관리자 계정 (관리자 권한이 소셜 계정 보안에 의존하지 않도록) |
| 404 | `SOCIAL_PROVIDER_NOT_SUPPORTED` | 없는·꺼진 공급자 |
| 409 | `SOCIAL_PROVIDER_ALREADY_LINKED` | 이 회원에 같은 공급자가 이미 연결됨 → 해제 후 연결 |

#### 내 소셜 계정 `GET /api/users/me/social-accounts`
```json
{ "hasPassword": true, "accounts": [ { "provider": "kakao", "linkedAt": "2026-11-21T12:00:00" } ] }
```
#### 연결 해제 `DELETE /api/users/me/social-accounts/{provider}` → `204`
- 404: 연결되지 않은 공급자 / 409 `SOCIAL_LAST_LOGIN_METHOD`: 비밀번호 없는 회원의 마지막 소셜 계정 (로그인할 방법이 없어짐)

#### 콜백 `?error=` 값
| code | 상황 | 화면 |
|---|---|---|
| `OAUTH2_CANCELLED` | 동의 화면에서 취소 (`error=access_denied`) | 취소 안내 |
| `OAUTH2_REQUEST_EXPIRED` | 시작 기록 쿠키 없음·10분 초과·다른 탭이 덮어씀 | 처음부터 다시 |
| `SOCIAL_LINK_EXPIRED` | 연결 요청 쿠키 없음·5분 초과·변조 | 내 정보에서 다시 연결 |
| `SOCIAL_ACCOUNT_ALREADY_LINKED` | 연결하려는 소셜 계정을 이미 다른 회원이 사용 (회원 합치기는 하지 않음) | 그 계정으로 로그인 |
| `SOCIAL_PROVIDER_ALREADY_LINKED` · `SOCIAL_LINK_FORBIDDEN` | 연결 API와 같음 (시작 후 상태가 바뀐 경우) | |
| `OAUTH2_FAILED` | state 불일치·코드 교환 실패·공급자 장애·ID 토큰 검증 실패 (원인은 서버 로그) | 다시 시도 |

---

## 매장

### 매장 생성 `POST /api/stores`
Request
```json
{ "name": "치킨집" }
```
- `name`: 필수, 50자 이하
- 점주는 요청 본문이 아니라 토큰의 회원으로 정해짐

Response `201 Created`
```json
{ "id": 1, "name": "치킨집", "ownerId": 1,
  "createdAt": "2026-11-05T14:00:00", "updatedAt": "2026-11-05T14:00:00" }
```

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패 |
| 401 | 토큰 없음 |
| 403 | CUSTOMER가 요청 |

### 매장 목록 조회 `GET /api/stores` / 단건 조회 `GET /api/stores/{id}`
Response `200 OK` — 매장 생성 응답 형식 (목록은 배열, [페이지네이션](#공통-사항) `?page=&size=`)

| 코드 | 상황 |
|---|---|
| 400 | `page`·`size` 범위 초과 (목록) |
| 404 | 매장 없음 (단건 조회) |

### 매장별 주문 조회 `GET /api/stores/{id}/orders`
Response `200 OK` — 주문 생성 응답 형식의 배열, 해당 매장에 들어온 주문만 ([페이지네이션](#공통-사항) `?page=&size=`)

| 코드 | 상황 |
|---|---|
| 400 | `page`·`size` 범위 초과 |
| 401 | 토큰 없음 |
| 403 | CUSTOMER가 요청, 본인 매장이 아님 |
| 404 | 매장 없음 |

### 매장별 정산 조회 `GET /api/stores/{id}/settlements`
기간 안에 **배달완료**된 결제의 합계와 건별 내역 (기준은 [정산 기준](settlement-policy.md))

Request — 쿼리 파라미터
```
GET /api/stores/1/settlements?from=2026-11-01&to=2026-11-05
```
- `from`, `to`: 필수, `yyyy-MM-dd`, 둘 다 포함하는 날짜 → 배달완료 시각 `[from 00:00, to+1 00:00)` (Asia/Seoul)
- `from ≤ to`, 최대 31일
- 확정된 날짜까지만 조회 가능 (D일은 D+1 01:00 이후 확정 → 오늘은 조회 불가)

Response `200 OK`
```json
{
  "storeId": 1, "storeName": "치킨집",
  "from": "2026-11-01", "to": "2026-11-05",
  "paymentCount": 2,
  "totalAmount": 38009, "totalFee": 1900, "settlementAmount": 36109,
  "refundCount": 0, "refundAmount": 0, "refundFee": 0, "refundSettlementAmount": 0,
  "netSettlementAmount": 36109,
  "refunds": [],
  "items": [
    { "paymentId": 3, "orderId": 5, "amount": 18009, "feeRate": 0.0500, "feeAmount": 900, "settlementAmount": 17109,
      "paidAt": "2026-11-01T18:20:00", "deliveredAt": "2026-11-01T19:05:00" },
    { "paymentId": 4, "orderId": 6, "amount": 20000, "feeRate": 0.0500, "feeAmount": 1000, "settlementAmount": 19000,
      "paidAt": "2026-11-05T20:10:00", "deliveredAt": "2026-11-05T21:00:00" }
  ]
}
```
- 합계는 건별 금액을 더하기만 함 → `totalFee + settlementAmount = totalAmount`
- `items`는 배달완료 시각 순, 대상이 없으면 합계 0과 빈 배열
- 판매(`paymentCount` ~ `items`)는 배달완료 시각 기준 — 나중에 환불돼도 빠지지 않음 → 한 번 조회한 금액이 바뀌지 않음
- 환불(`refund*`, `refunds`)은 환불 시각이 기간 안인 결제, `netSettlementAmount` = 판매 정산액 - 환불 정산액 (음수 가능)
  - `refunds` 항목: `paymentId`, `orderId`, `amount`, `feeAmount`, `settlementAmount`, `deliveredAt`, `refundedAt`
  - 이월·실제 지급액은 일 정산 기록 기준이라 이 응답에는 없음 ([정산 기준 9장](settlement-policy.md#9-결제-취소환불과-차기-정산-반영))
- 수수료·정산액은 OWNER 전용 응답에만 포함 (CUSTOMER의 결제 응답 `PaymentResponse`와 분리)

| 코드 | 상황 |
|---|---|
| 400 | `from`/`to` 누락·형식 오류, 시작일 > 종료일, 31일 초과, 확정 전 날짜 포함 |
| 401 | 토큰 없음 |
| 403 | CUSTOMER가 요청, 본인 매장이 아님 |
| 404 | 매장 없음 |

---

### 매장별 일 정산 기록 조회 `GET /api/stores/{id}/daily-settlements`
저장된 **일 정산 기록**(실제 지급액·이월·상태) 목록. 위의 기간 정산 조회는 결제로 다시 계산한 "발생 기준" 내역이고, 이 API는 "지급 기준" 기록

| | 기간 정산 조회 (`/settlements`) | 일 정산 기록 (`/daily-settlements`) |
|---|---|---|
| 환불 | 환불 시각 기준 날짜 | 실제로 차감된 정산 기준 (늦게 반영된 환불은 다음 정산) |
| 금액 | 순 정산액 (음수 가능) | 실제 지급액(≥ 0) + 이월 |
| 상태 | 없음 | `PENDING` / `CONFIRMED` / `PAID`, 확정·지급 시각 |

Request — 쿼리 파라미터
```
GET /api/stores/1/daily-settlements?from=2026-11-01&to=2026-11-30&status=PAID
```
- `from`, `to`: 필수, `yyyy-MM-dd`, `from ≤ to`, 최대 31일. 확정 전 날짜가 포함돼도 됨 (집계 전 날짜는 기록이 없을 뿐)
- `status`: 선택, `PENDING` / `CONFIRMED` / `PAID` — 없으면 전체 (확정 대기 포함, 금액은 집계 후 바뀌지 않음)

Response `200 OK` — 정산일 순, 기록이 없으면 빈 배열
```json
{
  "storeId": 1, "storeName": "치킨집", "from": "2026-11-01", "to": "2026-11-30",
  "settlements": [
    { "id": 3, "settlementDate": "2026-11-05",
      "paymentCount": 2, "totalAmount": 20000, "totalFee": 1000, "salesSettlementAmount": 19000,
      "refundCount": 0, "refundAmount": 0, "refundFee": 0, "refundSettlementAmount": 0,
      "carriedInAmount": 0, "carriedOutAmount": 0, "settlementAmount": 19000,
      "status": "PAID", "aggregatedAt": "2026-11-06T01:10:00",
      "confirmedAt": "2026-11-06T09:00:00", "paidAt": "2026-11-06T10:00:00" }
  ]
}
```

| 코드 | 상황 |
|---|---|
| 400 | `from`/`to` 누락·형식 오류, 시작일 > 종료일, 31일 초과, `status`가 정의되지 않은 값 |
| 401 | 토큰 없음 |
| 403 | OWNER가 아님(CUSTOMER·ADMIN), 본인 매장이 아님 |
| 404 | 매장 없음 |

### 매장별 일 정산 기록 상세 `GET /api/stores/{id}/daily-settlements/{settlementId}`
정산 1건이 어떤 판매·환불·이월로 구성됐는지

Response `200 OK`
```json
{
  "storeId": 1, "storeName": "치킨집",
  "settlement": { "id": 4, "settlementDate": "2026-11-06", "settlementAmount": 0, "carriedOutAmount": 9500, "status": "PENDING", "...": "목록 항목과 같음" },
  "sales": [ { "paymentId": 5, "orderId": 7, "amount": 10000, "feeRate": 0.0500, "feeAmount": 500, "settlementAmount": 9500,
               "paidAt": "2026-11-06T11:00:00", "deliveredAt": "2026-11-06T12:00:00" } ],
  "refundDeductions": [ { "paymentId": 1, "orderId": 1, "amount": 10000, "feeAmount": 500, "settlementAmount": 9500,
                          "deliveredAt": "2026-11-05T12:00:00", "refundedAt": "2026-11-06T14:00:00" } ],
  "carriedIn": [],
  "carriedOut": { "amount": 9500, "deducted": false, "toSettlementId": null, "toSettlementDate": null }
}
```
- `sales`: 그날 배달완료된 결제 (나중에 환불돼도 포함 → 정산의 판매 합계와 항상 같음), 배달완료 시각 순
- `refundDeductions`: 이 정산에서 **실제로 차감한** 환불 (금액은 차감 기록 스냅샷), 환불 시각 순
- `carriedIn`: 이전 정산에서 넘어와 이 정산에서 차감한 이월분 (`fromSettlementId`, `fromSettlementDate`, `amount`)
- `carriedOut`: 이 정산이 넘긴 이월분, 없으면 `null`. `deducted: false`면 아직 다음 판매·환불이 없어 차감 전

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | OWNER가 아님, 본인 매장이 아님 |
| 404 | 매장 없음, 정산이 없거나 **다른 매장의 정산** (다른 매장에 그 정산이 있는지 드러내지 않음) |

### 내 정산 내역 `GET /api/settlements`
점주 본인 매장 **전체**의 일 정산 기록을 한 목록으로 (매장별 `/daily-settlements`와 같은 저장 기록, 항목에 매장 정보 추가)

Request — 쿼리 파라미터 (모두 선택)
```
GET /api/settlements?storeId=1&status=PAID&from=2026-11-01&to=2026-11-30&page=0&size=20
```
- `storeId`: 이 매장만. 없으면 본인 매장 전체
- `status`: `PENDING` / `CONFIRMED` / `PAID`, 없으면 전체
- `from`, `to`: `yyyy-MM-dd`, 정산일 기준이며 둘 다 포함. 한쪽만 줘도 되고, 기간 길이 제한 없음 (페이징으로 응답 크기 제한)
- `page`, `size`: 목록 공통 페이징 (기본 0·20, `size` 최대 100), **id 오름차순**

Response `200 OK` — 본문은 배열, 페이지 정보는 헤더 `X-Page`·`X-Size`·`X-Has-Next` ([성능 개선](performance.md)과 같은 방식)
```json
[
  { "id": 3, "storeId": 1, "storeName": "치킨집", "settlementDate": "2026-11-05",
    "paymentCount": 2, "totalAmount": 20000, "totalFee": 1000, "salesSettlementAmount": 19000,
    "refundCount": 0, "refundAmount": 0, "refundFee": 0, "refundSettlementAmount": 0,
    "carriedInAmount": 0, "carriedOutAmount": 0, "settlementAmount": 19000,
    "status": "PAID", "aggregatedAt": "2026-11-06T01:10:00",
    "confirmedAt": "2026-11-06T09:00:00", "paidAt": "2026-11-06T10:00:00" }
]
```
- 본인 매장 id를 먼저 정한 뒤 `store_id IN (...)`으로 조회 → 다른 점주의 정산은 섞이지 않음
- 관리자 응답의 처리자(`confirmedBy`·`paidBy`)는 포함하지 않음. 매장이 없는 점주는 빈 배열
- 재집계(#39)로 과거 날짜 정산이 나중에 만들어지면 id 순과 정산일 순이 다를 수 있음 → 날짜순이 필요하면 클라이언트에서 정렬하거나 `from`·`to`로 좁혀 조회

| 코드 | 상황 |
|---|---|
| 400 | `from` > `to`, 날짜 형식 오류, `status`가 정의되지 않은 값, `page` < 0, `size`가 1~100 밖 |
| 401 | 토큰 없음 |
| 403 | OWNER가 아님(CUSTOMER·ADMIN), `storeId`가 본인 매장이 아님 |
| 404 | `storeId` 매장 없음 |

---

## 메뉴

### 메뉴 등록 `POST /api/menus`
Request
```json
{ "storeId": 1, "name": "후라이드 치킨", "price": 18000, "description": "바삭한 치킨" }
```
- `storeId`: 필수, 본인 매장이어야 함
- `name`: 필수, 50자 이하
- `price`: 필수, 1원 이상
- `description`: 선택, 255자 이하

Response `201 Created`
```json
{ "id": 1, "storeId": 1, "name": "후라이드 치킨", "price": 18000, "description": "바삭한 치킨",
  "createdAt": "2026-10-08T14:00:00", "updatedAt": "2026-10-08T14:00:00" }
```

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패 |
| 401 | 토큰 없음 |
| 403 | CUSTOMER가 요청, 본인 매장이 아님 |
| 404 | 매장 없음 |

### 메뉴 목록 조회 `GET /api/menus`
Response `200 OK` — 삭제되지 않은 메뉴만 반환 ([페이지네이션](#공통-사항) `?page=&size=`, 범위 초과 시 400)
```json
[
  { "id": 1, "storeId": 1, "name": "후라이드 치킨", "price": 18000, "description": "바삭한 치킨",
  "createdAt": "2026-10-08T14:00:00", "updatedAt": "2026-10-08T14:00:00" }
]
```

### 메뉴 단건 조회 `GET /api/menus/{id}`
Response `200 OK` — 메뉴 등록 응답과 같은 형식

| 코드 | 상황 |
|---|---|
| 404 | 메뉴가 없거나 삭제됨 |

### 메뉴 수정 `PUT /api/menus/{id}`
Request — 메뉴 등록에서 `storeId`를 뺀 형식 (매장은 변경 불가)

Response `200 OK` — 메뉴 등록과 같은 형식

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패 |
| 401 | 토큰 없음 |
| 403 | 본인 매장의 메뉴가 아님 (CUSTOMER 포함) |
| 404 | 메뉴가 없거나 삭제됨 |

### 메뉴 삭제 `DELETE /api/menus/{id}`
Response `204 No Content`
- 실제로 지우지 않고 `deleted = true`로 변경 (Soft Delete) → 기존 주문 기록 유지

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | 본인 매장의 메뉴가 아님 (CUSTOMER 포함) |
| 404 | 메뉴가 없거나 이미 삭제됨 |

---

## 주문

### 주문 생성 `POST /api/orders`
Request
```json
{
  "items": [
    { "menuId": 1, "quantity": 2 },
    { "menuId": 2, "quantity": 1 }
  ],
  "zipCode": "06236",
  "roadAddress": "서울 강남구 테헤란로 152 (역삼동)",
  "detailAddress": "101동 1203호",
  "deliveryRequest": "문 앞에 두고 벨 눌러 주세요"
}
```
- `items`: 필수, 1~50개. 모두 같은 매장의 메뉴여야 함
  - `menuId`: 필수, 같은 메뉴는 한 항목으로 합쳐서 요청 (중복 시 400)
  - `quantity`: 1 이상
- 배송 주소 (#77, V18) — 다음 우편번호 서비스 검색 결과 + 사용자가 입력한 상세 주소. 앞뒤 공백은 서버가 지움
  - `zipCode`: 필수, 숫자 5자리
  - `roadAddress`: 필수, 150자 이하
  - `detailAddress`: 선택, 100자 이하 (빈 값이면 `null`로 저장)
  - 줄바꿈·탭 등 제어 문자 거부. 위반은 400 + `code: INVALID_ADDRESS`
  - 예전 한 줄 `deliveryAddress`는 더 이상 받지 않음 (보내도 무시 → 우편번호 누락 400)
  - 데모 화면은 기본으로 체험용 주소 `00000` / `체험용 주소 (실제 배달되지 않음)`를 보냄 (실제 집 주소가 저장되지 않도록, 서버는 형식만 검증)
- `deliveryRequest`: 배달 요청사항, 선택, 앞뒤 공백 제외 100자 이하, 제어 문자 거부(V19). 비면 `null`로 저장. 위반은 400 + `code: INVALID_DELIVERY_REQUEST`
- 총액은 요청으로 받지 않고 서버에서 `항목별 메뉴 가격 × 수량의 합`으로 계산
- 매장은 요청으로 받지 않고 메뉴의 매장으로 연결

Response `201 Created`
```json
{
  "id": 1, "storeId": 1, "storeName": "치킨집",
  "items": [
    { "menuId": 1, "menuName": "후라이드 치킨", "unitPrice": 18000, "quantity": 2, "linePrice": 36000 },
    { "menuId": 2, "menuName": "콜라", "unitPrice": 2000, "quantity": 1, "linePrice": 2000 }
  ],
  "totalPrice": 38000,
  "deliveryAddress": "서울 강남구 테헤란로 152 (역삼동) 101동 1203호",
  "zipCode": "06236", "roadAddress": "서울 강남구 테헤란로 152 (역삼동)", "detailAddress": "101동 1203호",
  "deliveryRequest": "문 앞에 두고 벨 눌러 주세요", "status": "ORDER_REQUEST",
  "createdAt": "2026-10-08T14:03:00", "updatedAt": "2026-10-08T14:03:00",
  "deliveredAt": null
}
```
- `menuName`, `unitPrice`는 현재 메뉴가 아닌 주문 당시 값
- `deliveredAt`: 배달완료 시각, 배달완료 전에는 `null`
- `deliveryAddress`: 한 줄 주소(도로명 + 공백 + 상세) — 기존 화면 호환용으로 계속 채움
- `zipCode`·`roadAddress`·`detailAddress`: V18 이전 주문은 모두 `null` (한 줄 주소를 안전하게 나눌 수 없어 백필하지 않음) → 표시는 `deliveryAddress` 기준

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패, 주소 형식(`INVALID_ADDRESS`), 요청사항 형식(`INVALID_DELIVERY_REQUEST`), 같은 메뉴 중복(`DUPLICATE_ORDER_ITEM`), 다른 매장의 메뉴가 섞임(`MIXED_STORE_ORDER`), 총액이 너무 큼(`ORDER_AMOUNT_TOO_LARGE`) |
| 401 | 토큰 없음 |
| 403 | OWNER가 요청 |
| 404 | 항목 중 하나라도 메뉴가 없거나 삭제됨 (`MENU_NOT_FOUND`) |

### 주문 목록 조회 `GET /api/orders`
Response `200 OK` — 주문 생성 응답 형식의 배열 ([페이지네이션](#공통-사항) `?page=&size=` 또는 keyset `?afterId=&size=`)
- CUSTOMER: 본인이 한 주문만
- OWNER: 본인의 모든 매장에 들어온 주문만 (매장 하나만 보려면 `GET /api/stores/{id}/orders`)

| 코드 | 상황 |
|---|---|
| 400 | `page`·`size`·`afterId` 범위 초과·형식 오류, `page`와 `afterId`를 함께 보냄 |
| 401 | 토큰 없음 |

### 주문 단건 조회 `GET /api/orders/{id}`
Response `200 OK` (`OrderDetailResponse`, #62) — 주문 생성 응답 필드 + `payment`
```json
{
  "id": 12, "storeId": 2, "storeName": "치킨집",
  "items": [{ "menuId": 1, "menuName": "치킨", "unitPrice": 18000, "quantity": 2, "linePrice": 36000 }],
  "totalPrice": 36000, "deliveryAddress": "서울 강남구 테헤란로 152 101호",
  "zipCode": "06236", "roadAddress": "서울 강남구 테헤란로 152", "detailAddress": "101호", "status": "PAYMENT_COMPLETED",
  "createdAt": "2026-11-19T12:00:00", "updatedAt": "2026-11-19T12:01:00", "deliveredAt": null,
  "payment": {
    "id": 7, "amount": 36000, "paymentMethod": "CARD", "status": "COMPLETED",
    "paidAt": "2026-11-19T12:01:00", "cancelledAt": null, "refundedAt": null
  }
}
```
- CUSTOMER: 본인 주문만 / OWNER: 본인 매장에 들어온 주문만 — 역할·소유권은 토큰이 아니라 DB 기준
- `payment`: 결제 전 주문(결제 없이 취소된 주문 포함)은 `null`. 수수료·정산액은 포함하지 않음
- 주문·매장·항목은 fetch join 1번 + 결제 1번 → 항목 수와 무관하게 쿼리 3번(회원 포함)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음, 회원이 삭제됨 |
| 403 | ADMIN이 요청, 본인 주문·본인 매장 주문이 아님 |
| 404 | 주문 없음 |

### 주문 취소 `PATCH /api/orders/{id}/cancel`
Response `204 No Content` — 주문 상태를 `ORDER_CANCELLED`로 변경
- 주문수락 전(`ORDER_REQUEST`, `PAYMENT_COMPLETED`)에만 가능
- 결제완료 주문이면 결제도 `CANCELLED`로 바뀌고 취소 시각(`cancelledAt`)을 기록 → 고객 결제액 전액 취소. 배달 전이라 정산에는 영향 없음
- 주문 행을 잠그고 처리 → 사장님 수락과 동시에 들어와도 한쪽만 성공 (수락된 주문이 취소되지 않음)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | OWNER가 요청, 본인 주문이 아님 |
| 404 | 주문 없음 |
| 409 | 주문수락 이후(`ORDER_NOT_CANCELLABLE`, 배달완료 후에는 관리자 환불), 이미 취소됨(`ORDER_ALREADY_CANCELLED`) |

### 주문 상태 변경 `PATCH /api/orders/{id}/status`
Request
```json
{ "status": "ORDER_ACCEPTED" }
```
- 허용되는 변경: `PAYMENT_COMPLETED → ORDER_ACCEPTED`, `ORDER_ACCEPTED → DELIVERY_COMPLETED`
- `DELIVERY_COMPLETED`로 변경하면 서버 현재 시각(Asia/Seoul)을 배달완료 시각(`deliveredAt`)으로 기록 → 정산 기간 기준

Response `204 No Content`

| 코드 | 상황 |
|---|---|
| 400 | status 누락 또는 잘못된 값 |
| 401 | 토큰 없음 |
| 403 | CUSTOMER가 요청, 본인 매장에 들어온 주문이 아님 |
| 404 | 주문 없음 |
| 409 | 허용되지 않는 변경 (결제 전 주문 수락, 단계 건너뛰기, 배달완료 이후 변경 등) — `INVALID_ORDER_STATUS` |

---

## 결제

### 결제 `POST /api/payments`
Request
```json
{ "orderId": 1, "paymentMethod": "CARD" }
```
- `paymentMethod`: `CARD`만 가능
- 결제 금액은 요청으로 받지 않고 주문 총액(`totalPrice`)을 그대로 사용
- 수수료·정산액은 응답에 포함하지 않음 ([금액 처리 정책](money-policy.md) 참고)

Response `201 Created` — 결제 내역 저장 후 주문 상태를 `PAYMENT_COMPLETED`로 변경
```json
{
  "id": 1, "orderId": 1, "amount": 36000, "paymentMethod": "CARD",
  "orderStatus": "PAYMENT_COMPLETED", "paidAt": "2026-10-08T14:05:00",
  "status": "COMPLETED", "cancelledAt": null, "refundedAt": null
}
```
- `status`: 결제 상태 `COMPLETED` / `CANCELLED`(배달 전 취소) / `REFUNDED`(배달 후 환불)

#### 멱등성 키 (선택) — `Idempotency-Key` 헤더 (#41)
응답을 받지 못해(타임아웃 등) 다시 보내는 요청을 같은 결제로 처리하기 위한 헤더. 없으면 기존과 똑같이 동작
```
Idempotency-Key: 3f6c1e0a-8d2b-4c7e-9a51-2b7d0c9e4f13
```
- 값: 영문·숫자·`-`·`_` 1~64자 (UUID 권장). 사용자별로 구분 → 다른 사용자의 같은 키와 섞이지 않음
- 같은 키 + 같은 요청(`orderId`·`paymentMethod`)이 다시 오면 결제하지 않고 **처음 응답(201·본문)을 그대로** 돌려주고 `Idempotent-Replayed: true` 헤더를 붙임
  - 보관 기간: 결제 **완료 시점부터 30일** (Redis 완료 기록 24시간 + 결제와 함께 저장한 DB 기록 30일). 지난 뒤 같은 키로 보내면 새 요청으로 처리되어, 이미 결제된 주문이므로 409
  - 재전송하는 본문은 **결제 직후의 응답** — 그 뒤 주문이 배달완료·환불됐어도 처음 값(`PAYMENT_COMPLETED`·`COMPLETED`)이므로, 현재 상태는 [결제 단건 조회](#결제-단건-조회-get-apipaymentsid)로 확인
  - 처리 중 기록은 최대 1분 — 그 사이 같은 키 요청은 409(처리 중). 결제가 커밋된 뒤라면 처리 중 기록이 남아 있어도 DB 기록으로 처음 응답을 재전송
- 결제가 실패한 요청(4xx·5xx)은 기록하지 않음 → 같은 키로 다시 시도 가능 (같은 이유면 같은 오류)
- 키와 관계없이 **주문당 결제는 1건** — 다른 키로 이미 결제된 주문을 결제하면 기존처럼 409
- 저장소(Redis)를 쓸 수 없으면 새 결제는 하지 않고 503 → 잠시 뒤 같은 키로 재시도 (키 없는 요청은 영향 없음)
  - 결제가 이미 끝난 키라면 Redis 장애 중에도 DB 기록으로 처음 응답을 재전송 (결제가 다시 되지는 않음)
- TTL·장애 시 동작 상세는 [프로젝트 구조 — Redis](architecture.md#redis-41)

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 누락, 결제 수단이 CARD가 아님(`UNSUPPORTED_PAYMENT_METHOD`), `Idempotency-Key` 형식 오류(`INVALID_IDEMPOTENCY_KEY`) |
| 401 | 토큰 없음 |
| 403 | OWNER가 요청, 본인 주문이 아님 |
| 404 | 주문 없음 (`ORDER_NOT_FOUND`) |
| 409 | 이미 결제됨·중복 결제(`ORDER_ALREADY_PAID`), 취소된 주문(`ORDER_ALREADY_CANCELLED`), 같은 `Idempotency-Key`의 결제가 처리 중이거나 같은 키로 다른 주문이 동시에 결제됨(`PAYMENT_IN_PROGRESS`) |
| 422 | 같은 `Idempotency-Key`로 다른 결제 요청 (`orderId`·`paymentMethod`가 처음과 다름) — `IDEMPOTENCY_KEY_REUSED` |
| 503 | `Idempotency-Key`가 있는데 Redis 장애·타임아웃이고, 그 키로 완료된 결제가 없음 (결제하지 않음) — `PAYMENT_UNAVAILABLE` |

### 내 결제 목록 조회 `GET /api/payments`
Response `200 OK` — 결제 단건 조회와 같은 형식(`PaymentResponse`)의 배열 ([페이지네이션](#공통-사항) `?page=&size=`, id 오름차순, #62)
- 본인 주문의 결제만 (결제자 = 주문자). 취소·환불된 결제도 포함하며 각 항목은 현재 상태
- 다음 페이지 여부는 `X-Has-Next` 헤더. 인덱스 근거는 [performance.md 10장](performance.md#10-내-결제-목록-62)

| 코드 | 상황 |
|---|---|
| 400 | `page`·`size` 범위 초과 |
| 401 | 토큰 없음, 회원이 삭제됨 |
| 403 | OWNER·ADMIN이 요청 (토큰 역할이 CUSTOMER여도 DB 역할이 다르면 403) |

### 결제 단건 조회 `GET /api/payments/{id}`
결제의 **현재** 상태 조회 (#53). 결제 응답을 받지 못했을 때(타임아웃 등) 같은 `Idempotency-Key`로 재전송하기 전에 결과를 확인하는 용도

Response `200 OK` — 결제 응답과 같은 형식(`PaymentResponse`)
```json
{
  "id": 1, "orderId": 1, "amount": 36000, "paymentMethod": "CARD",
  "orderStatus": "DELIVERY_COMPLETED", "paidAt": "2026-10-08T14:05:00",
  "status": "REFUNDED", "cancelledAt": null, "refundedAt": "2026-10-09T10:00:00"
}
```
- 멱등성 재전송 본문(결제 직후 값)과 달리 취소·환불·배달 진행이 반영된 현재 값 (`orderStatus`·`status`·`cancelledAt`·`refundedAt`)
- 수수료·정산액은 포함하지 않음 (결제 응답과 같음)
- 본인 주문의 결제만 조회 — 결제자 = 주문자. OWNER는 매장 주문 조회·정산 조회 사용

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음, 회원이 삭제됨 |
| 403 | OWNER·ADMIN이 요청, 본인 결제가 아님 |
| 404 | 결제 없음 |

### 토스페이먼츠 결제 (V20, 테스트 모드)
공식 문서의 **공개 테스트 키**로 결제창(SDK v2 `payment().requestPayment`, 카드)을 열고 서버가 승인하는 흐름. 실제 돈은 오가지 않음
- 키: `TOSS_CLIENT_KEY`(`test_ck_…`)·`TOSS_SECRET_KEY`(`test_sk_…`) 환경변수로만(`.env`). 둘 다 없으면 토스 결제 꺼짐 → 화면은 모의 결제(`POST /api/payments`). 하나만 있거나 `live_` 키면 기동 실패
- 모의 결제(`POST /api/payments`)는 그대로 둠 — 테스트·부하 테스트·키 없는 로컬 데모용
- **가짜 PG 모드** (`TOSS_MODE=fake`, V21): 토스 서버에 아예 접속하지 않고 결제창·승인·조회·취소를 메모리에서 흉내(`pg.FakeTossPaymentsClient`). 키 없이 시연, 장애 재현용 — prod 프로필에서는 기동 실패
  - 화면은 토스 SDK 대신 `/payments/fake-toss`(가짜 결제창)로 이동 → 결과는 실제와 같은 `successUrl`·`failUrl` → 이후 서버 승인·금액 대조·멱등 처리는 실제와 같은 코드
  - 가짜 결제창의 인증 단계 `POST /api/payments/fake-toss/authorize {tossOrderId, amount, scenario}` → `{paymentKey}` (fake 모드에서만 존재, CUSTOMER)
  - `scenario`: `SUCCESS` / `CARD_REJECTED`(승인 거절) / `RESPONSE_LOST`(승인됐는데 응답 유실 → 서버가 조회로 확인해 기록) / `PG_DELAYED`(첫 승인 미처리 → 503 `PG_RESULT_UNKNOWN` → 같은 요청 재시도로 승인)
  - 한계: 메모리 보관(재시작하면 가짜 결제 기록이 사라짐), 인스턴스 1대

```
화면                         서버                                   토스
 │ GET  /toss/config  ───────▶ enabled·clientKey
 │ POST /toss/checkout ──────▶ 주문 확인 → tossOrderId·금액 (저장 없음)
 │ requestPayment ─────────────────────────────────────────────────▶ 결제창(카드 인증)
 │ ◀──────────── successUrl?order=&paymentKey=&orderId=&amount= ◀──┘
 │ POST /toss/confirm ───────▶ ① 결제 키 기록 있음? → 재전송
 │                             ② 금액 = 주문 총액? 주문번호 = 이 주문? 결제 가능한 상태?  (아니면 승인 안 함)
 │                             ③ 승인 (트랜잭션 밖, 멱등 키 confirm-{paymentKey}) ─────▶ /v1/payments/confirm
 │                             ④ 응답 대조 → 주문 행 잠금 → 결제 기록 (모의 결제와 같은 규칙)
 │ ◀─────────────────── 201 PaymentResponse
```

#### 결제 사용 여부 `GET /api/payments/toss/config` (CUSTOMER)
```json
{ "enabled": true, "clientKey": "test_ck_...", "mode": "REAL" }
```
- 클라이언트 키는 브라우저용 공개 값. 시크릿 키는 내려보내지 않음. 키를 서버 `.env` 한 곳에서만 관리하려고 프론트 빌드 변수 대신 이 API로 전달

#### 결제창 값 `POST /api/payments/toss/checkout` (CUSTOMER, 본인 주문)
Request `{ "orderId": 1 }` → Response `200`
```json
{ "clientKey": "test_ck_...", "customerKey": "cust_3f2a...(40자)", "tossOrderId": "order-1-9c1e0f6a2b3d4e5f",
  "amount": 36000, "orderName": "후라이드 치킨 외 1건" }
```
- `tossOrderId` = `order-{주문 id}-{난수 16자리}`: 공개 테스트 키는 여러 개발자가 함께 쓰므로 주문 id만으로는 토스 쪽에서 겹칠 수 있어 난수를 붙임. **저장하지 않음** — 승인 때 형식·주문 id를 확인하고 금액은 주문 총액과 다시 대조
- `customerKey`: 회원 id 해시(토스 규칙 22~50자) — 아이디·이메일 등을 토스에 보내지 않음
- 오류: 409 `ORDER_ALREADY_PAID`·`ORDER_ALREADY_CANCELLED`(결제할 수 없는 주문), 503 `PG_NOT_CONFIGURED`(키 없음)

#### 결제창 이동 주소 (프론트)
- 성공 `/payments/toss/success?order={주문 id}` — 토스가 `paymentKey`·`orderId`(토스 주문번호)·`amount`를 붙임. 이름이 겹치지 않도록 이 서비스의 주문 id는 `order`
- 실패 `/payments/toss/fail?order={주문 id}` — 토스가 `code`·`message`를 붙임 (`PAY_PROCESS_CANCELED` = 구매자가 창을 닫음). 서버 승인 전이라 결제되지 않음
- 인증 후 **10분 안에** 승인해야 함. 승인하지 않은 결제는 만료되고 청구되지 않음

#### 결제 승인 `POST /api/payments/toss/confirm` (CUSTOMER, 본인 주문)
Request (헤더 `Idempotency-Key` 선택 — 화면은 `tossOrderId`를 키로 보냄)
```json
{ "orderId": 1, "paymentKey": "tgen_...", "tossOrderId": "order-1-9c1e0f6a2b3d4e5f", "amount": 36000 }
```
Response `201 Created` — 모의 결제와 같은 `PaymentResponse`. 재전송이면 헤더 `Idempotent-Replayed: true`

| 상태 | code | 상황 | 토스 승인 | 화면 대응 |
|---|---|---|---|---|
| 400 | `PAYMENT_AMOUNT_MISMATCH` | `amount` ≠ 주문 총액 (또는 승인 응답 금액·주문번호가 요청과 다름 → 보상 취소) | 호출 안 함 | 주문에서 다시 결제 |
| 400 | `INVALID_PG_ORDER_ID` | `tossOrderId`가 이 주문에 발급한 형식이 아님 | 호출 안 함 | 주문에서 다시 결제 |
| 400 | `PG_PAYMENT_REJECTED` | 토스가 거절(카드 거절·결제 시간 만료 등), 결과 확인 결과 `ABORTED`·`EXPIRED` | 거절됨 | 결제창 다시 |
| 409 | `ORDER_ALREADY_PAID` / `ORDER_ALREADY_CANCELLED` | 승인 전 확인에서 걸림 → 승인 안 함 / **승인 후** 기록 시점에 걸림 → 보상 취소 | 상황별 | 주문 다시 불러오기 |
| 409 | `PAYMENT_IN_PROGRESS` · 422 `IDEMPOTENCY_KEY_REUSED` · 503 `PAYMENT_UNAVAILABLE` | `Idempotency-Key` 규칙 (모의 결제와 같음) | — | 같은 키로 재시도 / 새 키 |
| 503 | `PG_RESULT_UNKNOWN` | 승인 결과를 모름(시간 초과·연결 끊김·5xx) + 결제 조회로도 확인 못 함 | 됐을 수도 있음 | **결제창을 다시 열지 말고** 같은 요청 재시도 |
| 503 | `PG_NOT_CONFIGURED` | 키 없음, 시크릿 키 거부(401) | — | 결제되지 않음 |

중복 금전 처리를 막는 장치
- **승인 1번**: 토스 멱등 키 `confirm-{paymentKey}` — 같은 결제 키의 승인 재시도(시간 초과 후·동시 요청)에 토스가 처음 응답을 돌려줌. 결제 키 하나는 한 번만 승인됨(`ALREADY_PROCESSED_PAYMENT` → 조회해서 `DONE`이면 기록)
- **기록 1번**: `payments.pg_payment_key` UNIQUE + 주문당 결제 1건. 같은 결제 키가 이미 기록돼 있으면 토스를 다시 부르지 않고 처음 결과를 재전송
- **결과를 모르면 실패로 단정하지 않음**: 시간 초과 → `GET /v1/payments/{paymentKey}`로 확인 → `DONE`이면 기록, 승인 불가 상태면 400, 그 밖에는 503 `PG_RESULT_UNKNOWN`. 화면은 같은 요청을 1·2·4초 간격으로 자동 재시도하고, 그래도 모르면 '다시 확인' 버튼(같은 요청)만 보여 줌
- **승인됐는데 기록할 수 없으면 보상 취소**: 승인을 기다리는 사이 주문이 취소됨·다른 결제 키로 이미 결제됨 → `pg_cancel_requests`에 기록하고 토스 전액 취소. 단, 원인이 불분명한 기록 실패(DB 장애 등)는 취소하지 않음 — 같은 요청 재시도 때 토스가 승인 응답을 재전송해 기록됨
- 토스 응답을 기다리는 동안 DB 커넥션·주문 행 잠금을 잡지 않음(승인 전 확인·기록은 각각 짧은 트랜잭션). 시간 초과: 연결 3초, 승인 15초, 조회 5초 — 합계가 멱등성 `processing-ttl`(1분)보다 짧음

#### 취소·환불과 토스 결제 취소
- 주문 취소(`PATCH /api/orders/{id}/cancel`)·관리자 환불(전액 `PATCH .../refund`, 부분 `POST .../refunds`)의 응답·규칙은 그대로. 토스를 거친 결제면 같은 트랜잭션에 `pg_cancel_requests` 행을 남김 (취소 금액 `amount`, 반영 후 남아야 할 토스 잔액 `balance_after`)
- 토스 취소 API(`POST /v1/payments/{paymentKey}/cancel`, `cancelAmount` = 이번 금액)는 **커밋 후** 호출: 커밋 직후 1회 → 실패·시간 초과는 주기 작업(10초)이 백오프(10초부터 2배, 최대 10분)로 재시도, 10번 실패하면 `FAILED` + 알림 `PgCancelFailed`
  - 주문 행 잠금을 쥔 채 외부 응답을 기다리지 않고, 커밋이 실패했는데 돈만 돌려주는 일이 없도록
  - 중복 환불 없음: 두 번째 시도부터는 토스 결제를 먼저 조회해 **잔액 ≤ `balance_after`면 이미 반영**된 것으로 `DONE`(취소를 다시 보내지 않음). 부분 취소는 두 번 보내면 두 번 환불되므로 이 확인이 필수. 전액 취소된 결제의 `ALREADY_CANCELED_PAYMENT`도 `DONE`
  - 같은 결제의 취소 요청은 **생긴 순서대로 하나씩** — 앞 요청이 `DONE`이 아니면(PENDING·FAILED) 뒤 요청은 기다림 → 잔액 비교가 정확함
  - 멱등 키는 시도마다 다름(`cancel-{요청 id}-{시도 번호}`) — 토스는 같은 키의 **오류 응답**도 15일간 그대로 돌려주므로 일시 오류를 같은 키로 재시도하면 계속 실패함. 중복은 위의 잔액 확인이 막음
- 고객 화면은 취소 즉시 `CANCELLED`. 토스 쪽 환불이 끝났는지는 `pg_cancel_requests.status`로 확인(화면 표시는 후속)

---

## 관리자 — 정산

일 정산은 스케줄러가 매일 01:10에 전날분을 매장별로 집계해 `PENDING`으로 만들고, 관리자가 확정·지급한다 ([정산 기준 · 정산 집계·확정·지급](settlement-policy.md#8-정산-집계확정지급--settlements)).

```
PENDING(확정 대기) ── PATCH .../confirm ──▶ CONFIRMED(확정) ── PATCH .../pay ──▶ PAID(지급완료)
```

- 모든 관리자 API는 `ADMIN`만 (토큰 없음 401, CUSTOMER·OWNER 403)
- 정산 응답 형식 (`SettlementResponse`)
  ```json
  { "id": 1, "storeId": 1, "storeName": "치킨집", "settlementDate": "2026-11-05",
    "paymentCount": 2, "totalAmount": 38009, "totalFee": 1900, "salesSettlementAmount": 36109,
    "refundCount": 1, "refundAmount": 10000, "refundFee": 500, "refundSettlementAmount": 9500,
    "carriedInAmount": 0, "carriedOutAmount": 0, "settlementAmount": 26609,
    "status": "CONFIRMED",
    "aggregatedAt": "2026-11-06T01:10:00", "confirmedAt": "2026-11-06T09:00:00", "paidAt": null,
    "confirmedBy": { "id": 7, "username": "admin" }, "paidBy": null }
  ```
  - `confirmedBy`, `paidBy` (#39): 확정·지급한 관리자. 해당 단계 전이거나 V11 적용 전에 처리된 정산이면 `null`. 기존 필드는 그대로이고 뒤에 추가만 됨
  - 처리자는 요청으로 받지 않고 **토큰의 인증 정보**로 기록 ([정산 기준 10장](settlement-policy.md#10-감사-이력과-누락실패분-재집계-39))
  - `aggregatedAt`: 스케줄러 집계 시각, `confirmedAt`: 확정 시각(`PENDING`이면 `null`), `paidAt`: 지급 시각(`PAID`가 아니면 `null`)
  - `settlementAmount`: **실제 지급액** = max(판매 정산액 - 환불 정산액 - 이월받은 금액, 0). 모자라면 `carriedOutAmount`로 다음 정산에 이월

### 정산 목록 조회 `GET /api/admin/settlements`
Request — 쿼리 파라미터
```
GET /api/admin/settlements?date=2026-11-05&status=PENDING
```
- `date`: 필수, `yyyy-MM-dd` — 정산 날짜
- `status`: 선택, `PENDING` / `CONFIRMED` / `PAID` — 없으면 전체

Response `200 OK` — 정산 응답 배열 (id 순), 없으면 빈 배열

| 코드 | 상황 |
|---|---|
| 400 | `date` 누락·형식 오류, `status`가 정의되지 않은 값 |
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |

### 정산 확정 `PATCH /api/admin/settlements/{id}/confirm`
`PENDING` → `CONFIRMED`, 확정 시각(`confirmedAt`)을 서버 시각으로 기록. 요청 본문 없음

Response `200 OK` — 확정된 정산

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |
| 404 | 정산 없음 |
| 409 | `PENDING`이 아님 (이미 확정·지급됨) |

### 정산 지급 `PATCH /api/admin/settlements/{id}/pay`
`CONFIRMED` → `PAID`, 지급 시각(`paidAt`)을 서버 시각으로 기록. 요청 본문 없음

Response `200 OK` — 지급된 정산

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |
| 404 | 정산 없음 |
| 409 | `CONFIRMED`가 아님 — 확정 전(`PENDING`)이거나 이미 지급됨(`PAID`, 지급 시각은 처음 값 유지) |

- 같은 정산에 지급 요청이 동시에 들어와도 행 잠금으로 한 번만 성공, 나머지는 409
- 확정·지급에 성공하면 처리자·시각이 상태 변경 이력에 기록됨 (409로 거부된 요청은 기록되지 않음)

### 정산 상태 변경 이력 `GET /api/admin/settlements/{id}/histories`
정산 1건이 언제 누구에 의해 집계·확정·지급됐는지 기록 순으로 조회

Response `200 OK`
```json
[
  { "id": 1, "fromStatus": null, "toStatus": "PENDING", "actor": null, "changedAt": "2026-11-06T01:10:00" },
  { "id": 2, "fromStatus": "PENDING", "toStatus": "CONFIRMED",
    "actor": { "id": 7, "username": "admin" }, "changedAt": "2026-11-06T09:00:00" },
  { "id": 3, "fromStatus": "CONFIRMED", "toStatus": "PAID",
    "actor": { "id": 8, "username": "admin2" }, "changedAt": "2026-11-07T10:00:00" }
]
```
- `fromStatus`가 `null`인 첫 행은 집계. `actor`가 `null`이면 스케줄러, 값이 있으면 재집계한 관리자
- V11 적용 전에 일어난 전이는 이력이 없음 (빈 배열이거나 이후 전이만 나옴)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |
| 404 | 정산 없음 |

### 정산 재집계 `POST /api/admin/settlements/reaggregate`
집계 실패나 스케줄러 누락(catch-up 기간 초과)으로 **정산이 없는 매장·날짜**를 새로 집계해 `PENDING` 정산을 만든다. 이미 있는 정산은 상태와 관계없이 다시 계산하지 않음. 요청 본문 없음

Request — 쿼리 파라미터
```
POST /api/admin/settlements/reaggregate?date=2026-11-05            # 그 날짜의 집계 대상 매장 전체
POST /api/admin/settlements/reaggregate?date=2026-11-05&storeId=1  # 매장 1곳만
```
- `date`: 필수, `yyyy-MM-dd` — 집계 가능 시각(다음 날 01:00)이 지난 날짜
- `storeId`: 선택 — 없으면 그 날짜에 판매 또는 미차감 환불이 있는 매장 전체

Response `200 OK`
```json
{ "date": "2026-11-05", "created": 1, "skipped": 1, "failed": 1,
  "results": [
    { "storeId": 1, "outcome": "CREATED", "settlementId": 12, "error": null },
    { "storeId": 2, "outcome": "ALREADY_EXISTS", "settlementId": null, "error": null },
    { "storeId": 3, "outcome": "FAILED", "settlementId": null,
      "error": "DataIntegrityViolationException constraint=settlements_net_amount_check" }
  ] }
```
- `outcome`: `CREATED`(새 정산) / `ALREADY_EXISTS`(이미 정산이 있어 건너뜀) / `NO_TARGET`(집계할 판매·환불 없음) / `FAILED`(다시 실패 — 실패 기록에 남음)
- 일부 매장이 실패해도 200이며 매장별 결과로 구분 (매장 1곳 = 트랜잭션 1개)
- 같은 요청을 반복하거나 스케줄러와 동시에 실행돼도 매장·날짜당 정산은 1건 (뒤 요청은 `ALREADY_EXISTS`)

| 코드 | 상황 |
|---|---|
| 400 | `date` 누락·형식 오류, `storeId` 형식 오류, 아직 집계할 수 없는 날짜 |
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |
| 404 | `storeId`의 매장 없음 |
| 409 | `storeId`를 지정했는데 그 매장·날짜에 이미 정산이 있음 (`PENDING`·`CONFIRMED`·`PAID` 모두) |

### 정산 집계 실패 기록 `GET /api/admin/settlements/aggregation-failures`
Request — 쿼리 파라미터
```
GET /api/admin/settlements/aggregation-failures?status=FAILED
```
- `status`: 선택, `FAILED`(재집계 대상) / `RESOLVED` — 없으면 전체

Response `200 OK` — 최근 실패 순, 없으면 빈 배열
```json
[
  { "id": 1, "storeId": 3, "storeName": "분식집", "settlementDate": "2026-11-05",
    "status": "RESOLVED", "failureCount": 2,
    "lastError": "DataIntegrityViolationException constraint=settlements_net_amount_check",
    "firstFailedAt": "2026-11-06T01:10:00", "lastFailedAt": "2026-11-06T09:10:00",
    "lastFailedBy": { "id": 7, "username": "admin" },
    "resolvedAt": "2026-11-06T10:10:00", "resolvedBy": { "id": 8, "username": "admin2" }, "settlementId": 12 }
]
```
- `lastFailedBy`·`resolvedBy`가 `null`이면 스케줄러 실행. `settlementId`는 재집계로 만들어진 정산 (`FAILED`이거나 대상 없음으로 해결되면 `null`)
- `lastError`는 예외 종류와 제약 조건 이름만 담음. 상세 원인은 서버 로그

| 코드 | 상황 |
|---|---|
| 400 | `status`가 정의되지 않은 값 |
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |

---

## 관리자 — 결제

### 결제 환불 `PATCH /api/admin/payments/{id}/refund`
배달완료 주문의 결제에서 **남은 금액 전부** 환불 — `COMPLETED`·`PARTIALLY_REFUNDED` → `REFUNDED`, 환불 시각(`refundedAt`)을 서버 시각으로 기록. 요청 본문 없음
- 부분 환불이 없던 결제면 결제 금액 전부 = V21 이전 전액 환불과 같은 금액·수수료
- 고객 결제액·플랫폼 수수료·매장 정산액이 취소됨 — 환불 1건(`payment_refunds`)으로 기록되어 정산에서 차감
- 주문 상태는 `DELIVERY_COMPLETED` 그대로 (최종 상태)
- 정산은 여기서 바꾸지 않음 → 다음에 집계되는 정산에서 차감, 이미 만들어진 정산(확정·지급 포함)은 바뀌지 않음 ([정산 기준 9장](settlement-policy.md#9-결제-취소환불과-차기-정산-반영))
- 배달 전 주문은 고객의 주문 취소(`PATCH /api/orders/{id}/cancel`)로 처리

Response `200 OK` — 결제 응답 (`status: REFUNDED`)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |
| 404 | 결제 없음 |
| 409 | 배달완료 전 주문, 이미 환불·취소된 결제 (환불 시각은 처음 값 유지) |

- 같은 결제에 환불 요청이 동시에 들어와도 행 잠금으로 한 번만 성공, 나머지는 409
- 토스 결제면 커밋 후 토스 취소(남은 금액) 요청 — [토스페이먼츠 결제](#토스페이먼츠-결제-v20-테스트-모드)

### 결제 부분 환불 `POST /api/admin/payments/{id}/refunds` (V21)
배달완료 주문의 결제에서 **금액을 지정해** 환불. 남은 금액 안에서 여러 번 가능, 남은 금액 전부면 `REFUNDED`

Request
```json
{ "amount": 3000, "reason": "음료 누락" }
```
- `amount`: 양수 원 단위(필수), `reason`: 200자 이하(선택, 없으면 "환불")

Response `201 Created`
```json
{
  "refundId": 7, "paymentId": 1, "orderId": 1,
  "amount": 3000, "feeAmount": 150, "settlementAmount": 2850, "reason": "음료 누락",
  "refundedAt": "2026-11-06T10:00:00",
  "paymentStatus": "PARTIALLY_REFUNDED", "refundedAmount": 3000, "refundableAmount": 33000
}
```
- 수수료는 환불액 비율대로 함께 취소 — 누적 기준 반올림이라 여러 번 나눠도 합계가 전액 환불과 같음 ([금액 처리 정책](money-policy.md))
- 환불 1건마다 정산에서 따로 차감(환불 시각 이후 처음 집계되는 정산), 이미 만들어진 정산은 바뀌지 않음
- 결제 응답·주문 상세의 결제에 `refundedAmount`(누적 환불액) 추가, 상태 `PARTIALLY_REFUNDED` 추가
- 토스 결제면 커밋 후 이번 금액만큼 토스 **부분 취소**(`cancelAmount`) 요청 — 같은 결제의 부분 취소는 순서대로 하나씩, 결과를 모르는 재시도 전에는 토스 잔액으로 반영 여부 확인

| 코드 | 상황 |
|---|---|
| 400 | 금액 없음·0 이하, 사유 200자 초과 / `INVALID_REFUND_AMOUNT` 남은 금액 초과 |
| 401 · 403 · 404 | 토큰 없음 · ADMIN 아님 · 결제 없음 |
| 409 | 배달완료 전 주문(`PAYMENT_NOT_REFUNDABLE`), 이미 전액 환불·취소된 결제 |

- 같은 결제에 부분 환불이 동시에 들어와도 결제 행 잠금으로 순서대로 처리 → 누적 환불액이 결제액을 넘는 요청만 400

---

## 관리자 — 매장 수수료율

매장별 플랫폼 수수료율을 설정·해제한다 ([금액 처리 정책](money-policy.md)).
- 수수료율은 **결제 시점**에 정해져 결제(`payments.fee_rate`)에 저장됨 → 바꾼 값은 **이후 결제부터** 적용
- 이미 저장된 결제와 정산(확정·지급 포함)은 바뀌지 않음. 결제 후 배달완료 전에 바꿔도 그 결제는 결제 시점 수수료율로 정산
- 매장 수수료율이 없으면 공통 수수료율(`application.yml`의 `payment.fee.rate`) 사용
- 수수료율은 공개 매장 조회(`GET /api/stores`, `/api/stores/{id}`) 응답에 포함하지 않음
- 응답 형식 (`AdminStoreFeeRateResponse`)
  ```json
  { "storeId": 1, "storeName": "치킨집", "feeRate": 0.0300, "effectiveFeeRate": 0.0300, "defaultFeeRateApplied": false }
  ```
  - `feeRate`: 매장 수수료율 (해제 상태면 `null`)
  - `effectiveFeeRate`: 이후 결제에 실제로 적용되는 수수료율
  - `defaultFeeRateApplied`: `true`면 공통 수수료율 사용 중

### 매장 수수료율 설정 `PUT /api/admin/stores/{id}/fee-rate`
Request
```json
{ "feeRate": 0.03 }
```
- `feeRate`: 필수, 0 이상 1 이하, 소수점 4자리까지 (`0.03` = 3%). `0`은 수수료 0%(공통 수수료율로 대체되지 않음)

Response `200 OK` — 수수료율 응답

| 코드 | 상황 |
|---|---|
| 400 | `feeRate` 누락·`null`, 0 미만·1 초과, 소수점 5자리 이상, 숫자가 아님 |
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |
| 404 | 매장 없음 |

### 매장 수수료율 해제 `DELETE /api/admin/stores/{id}/fee-rate`
매장 수수료율을 지워 이후 결제부터 공통 수수료율을 사용. 요청 본문 없음, 이미 해제된 매장이어도 성공

Response `200 OK` — 수수료율 응답 (`feeRate: null`, `defaultFeeRateApplied: true`)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |
| 404 | 매장 없음 |

---

## 알림

### 내 알림 목록 `GET /api/notifications`
로그인한 사용자 본인이 받는 알림만 조회 (역할 무관). 결제 완료·환불은 고객과 사장님, 배달 완료는 고객에게 알림이 생김
- 페이지 단위: `?page=0&size=20` (size 최대 100), id 오름차순. 본문은 배열, 페이지 정보는 `X-Page`·`X-Size`·`X-Has-Next` 헤더
- 알림은 이벤트가 Kafka를 거쳐 처리된 뒤 생기므로 결제 직후에는 아직 없을 수 있음

Response `200 OK`
```json
[
  {
    "id": 1,
    "eventType": "PAYMENT_COMPLETED",
    "orderId": 10,
    "message": "주문 #10 결제가 완료되었습니다. (36,000원)",
    "createdAt": "2026-11-14T12:00:01",
    "sendStatus": "SENT",
    "sentAt": "2026-11-14T12:00:05",
    "read": true,
    "readAt": "2026-11-14T12:03:00"
  }
]
```
- `sendStatus`: `PENDING`(발송 대기·재시도 중) / `SENT` / `FAILED`(재시도 한도 초과) / `SKIPPED`(발송 기능 도입 전 기록)
- `read`·`readAt`(V15): 읽음 여부·처음 읽은 시각. 안 읽었으면 `false`·`null` (V15 이전 알림은 모두 안 읽음). 읽음과 발송 상태는 별개

| 코드 | 상황 |
|---|---|
| 400 | `page`·`size` 범위 오류 |
| 401 | 토큰 없음, 삭제된 회원 |

### 안 읽은 알림 개수 `GET /api/notifications/unread-count`
Response `200 OK`
```json
{ "unreadCount": 3 }
```
- 본인 알림 중 `readAt`이 없는 것만 (알림 배지용)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음, 삭제된 회원 |

### 알림 읽음 처리 `PATCH /api/notifications/{id}/read`
Response `204 No Content` — 요청 본문 없음
- 멱등: 이미 읽은 알림도 204이고 `readAt`은 처음 읽은 시각 그대로 (재시도·중복 클릭에 오류 없음)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음, 삭제된 회원 |
| 403 | 다른 사람의 알림 |
| 404 | 알림이 없음 |

### 사장님 실시간 주문 알림 `GET /api/owner/order-events` (#76)
접속 중인 사장님 화면에 새 주문(결제 완료)·고객 취소(환불)를 바로 알리는 SSE(Server-Sent Events) 연결. 응답 `text/event-stream`
- `storeId`(선택): 없으면 본인의 모든 매장, 있으면 그 매장만
- 요청 헤더: `Authorization: Bearer <Access>` + `Accept: text/event-stream` — 브라우저는 `EventSource` 대신 fetch 스트림 사용(토큰을 URL에 넣지 않기 위해, `frontend/README.md`)
- 전달 시점: 사장님 알림 기록(`notifications`)을 저장한 트랜잭션이 **커밋된 뒤** — 결제 직후가 아니라 Kafka를 거쳐 알림이 저장된 뒤
- 연결은 최대 10분(`owner-stream.timeout`) 후 서버가 정상 종료 → 클라이언트가 새 토큰으로 다시 연결 (그때 DB 기준 역할·매장 소유권을 다시 확인)
- **보장하지 않음**: 연결이 끊긴 동안의 이벤트는 다시 보내지 않음(`Last-Event-ID` 미지원). 클라이언트는 재연결 후 주문 목록을 다시 받아 맞춤. 알림 기록 자체는 `GET /api/notifications`에 남음

이벤트
```
event:connected        ← 연결 직후 1번 (구독 성공 확인용)
data:ok

:ping                  ← 25초마다 주석(하트비트) — 프록시 유휴 끊김 방지, 끊긴 연결 정리

event:order
id:42                  ← 알림 id
data:{"notificationId":42,"type":"PAYMENT_COMPLETED","orderId":77,"storeId":4,"amount":15000,
      "message":"새 주문 #77의 결제가 완료되었습니다. (15,000원)","occurredAt":"2026-11-21T12:00:00"}
```
- `type`: `PAYMENT_COMPLETED`(새 주문) / `PAYMENT_REFUNDED`(고객 취소 → 환불). 배달 완료는 사장님이 직접 바꾼 상태라 보내지 않음
- 본문은 토스트 문구용. 화면의 주문 목록은 이 값이 아니라 목록 API를 다시 받아 그림 (빠지거나 순서가 바뀐 이벤트에 영향받지 않도록)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음·만료(`code`로 구분, 공통 사항), 삭제된 회원 |
| 403 | OWNER가 아님(토큰 또는 DB 역할 기준), 다른 사장님의 매장(`storeId`) |
| 404 | `storeId` 매장이 없음 |
- 오류 본문은 `Accept: text/event-stream`이어도 공통 JSON 형식 (Content-Type 고정 — 고정 전에는 협상 실패로 500이 됨)

---

## 관리자 — Outbox 이벤트

Kafka 발행에 계속 실패해 `FAILED`가 된 이벤트를 확인하고 다시 발행 대상으로 되돌림 ([프로젝트 구조 — Kafka](architecture.md#kafka--transactional-outbox-42))

### 이벤트 목록 `GET /api/admin/outbox-events`
- `status`: `PENDING` / `PUBLISHED` / `FAILED` (기본 `FAILED`)
- 페이지 단위: `?page=0&size=20` (size 최대 100), id 오름차순. 페이지 정보는 응답 헤더

Response `200 OK`
```json
[
  {
    "id": 12,
    "eventId": "0b0c7c0e-3c56-4a57-9a55-7b6f0d0a2f11",
    "eventType": "PAYMENT_COMPLETED",
    "aggregateType": "ORDER",
    "aggregateId": 10,
    "status": "FAILED",
    "attemptCount": 10,
    "lastError": "org.apache.kafka.common.errors.TimeoutException: ...",
    "nextAttemptAt": "2026-11-14T12:08:31",
    "createdAt": "2026-11-14T12:00:00",
    "publishedAt": null
  }
]
```

### 이벤트 재발행 `POST /api/admin/outbox-events/{id}/republish`
`FAILED` → `PENDING`, 실패 횟수를 0으로 되돌림(마지막 실패 원인은 남김). 요청 본문 없음
- 여기서 Kafka로 바로 보내지 않음 → 다음 발행 주기(1초)에 Publisher가 발행하고, 다시 실패하면 같은 재시도 규칙을 따름
- 이벤트 내용·`eventId`는 그대로 → 이미 전달됐던 이벤트라면 Consumer가 중복으로 걸러 알림이 두 번 생기지 않음

Response `200 OK` — 이벤트 응답 (`status: PENDING`, `attemptCount: 0`)

### FAILED 일괄 재발행 `POST /api/admin/outbox-events/republish`
`FAILED` 이벤트를 오래된 순으로 `limit`건(기본·최대 100) 재발행

Response `200 OK`
```json
{ "republished": 3 }
```

| 코드 | 상황 |
|---|---|
| 400 | `status` 값 오류, `page`·`size`·`limit` 범위 오류 |
| 401 | 토큰 없음 |
| 403 | ADMIN이 아님 |
| 404 | 이벤트 없음 |
| 409 | `FAILED`가 아닌 이벤트 재발행 (동시에 재발행하면 한 번만 성공) |
