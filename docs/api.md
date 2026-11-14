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
    "message": "에러 메시지",
    "path": "/api/admin/payments/1/refund",
    "traceId": "5af890963e74404eb8ac559bfc252067"
  }
  ```
  - `message`는 기존과 같은 필드·문구 (기존 클라이언트는 그대로 동작), 나머지 필드는 #38에서 추가
  - 500은 내부 예외 메시지를 숨기고 공통 문구로 응답 → `traceId`로 서버 로그 확인
- 추적 헤더 `X-Trace-Id`
  - 모든 응답에 포함. 요청에 `X-Trace-Id`(영문·숫자·하이픈 8~64자)를 보내면 그 값을 그대로 사용, 없거나 형식이 다르면 서버가 생성
  - 같은 값이 에러 응답 `traceId`, 서버 로그 각 줄의 `[traceId]`에 들어감
- API 문서(Swagger UI): local·dev 프로필에서 `/swagger-ui.html`, 명세 `/v3/api-docs` (기본 설정·운영은 비활성 → 404)
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
- 공통 에러 코드

  | 코드 | 상황 |
  |---|---|
  | 400 | 요청 값 검증 실패, JSON 형식 오류, 필수 쿼리 파라미터 누락, 경로·쿼리 파라미터 형식 오류 |
  | 401 | 토큰이 없거나 유효하지 않음(만료·위조), 로그인 실패 |
  | 403 | 권한 없음 (역할이 다르거나 본인 데이터가 아님) |
  | 404 | 대상(매장·메뉴·주문·정산)이 없음 |
  | 409 | 중복(아이디, 결제) 또는 현재 상태에서 할 수 없는 요청 (주문·정산 상태 전이) |
  | 405 | 지원하지 않는 HTTP 메서드 (`Allow` 헤더 포함) |
  | 500 | 예상하지 못한 서버 오류 (`traceId`로 로그 확인) |

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
| 내 토큰 정보 조회 | GET | `/api/users/me` | O | 로그인 사용자 | 200 |
| 매장 생성 | POST | `/api/stores` | O | OWNER | 201 |
| 매장 목록 조회 | GET | `/api/stores?page=&size=` | X | - | 200 |
| 매장 단건 조회 | GET | `/api/stores/{id}` | X | - | 200 |
| 매장별 주문 조회 | GET | `/api/stores/{id}/orders?page=&size=` | O | OWNER (본인 매장) | 200 |
| 매장별 정산 조회 | GET | `/api/stores/{id}/settlements?from=&to=` | O | OWNER (본인 매장) | 200 |
| 매장별 일 정산 기록 목록 | GET | `/api/stores/{id}/daily-settlements?from=&to=&status=` | O | OWNER (본인 매장) | 200 |
| 매장별 일 정산 기록 상세 | GET | `/api/stores/{id}/daily-settlements/{settlementId}` | O | OWNER (본인 매장) | 200 |
| 메뉴 등록 | POST | `/api/menus` | O | OWNER (본인 매장) | 201 |
| 메뉴 목록 조회 | GET | `/api/menus?page=&size=` | X | - | 200 |
| 메뉴 단건 조회 | GET | `/api/menus/{id}` | X | - | 200 |
| 메뉴 수정 | PUT | `/api/menus/{id}` | O | OWNER (본인 매장 메뉴) | 200 |
| 메뉴 삭제 | DELETE | `/api/menus/{id}` | O | OWNER (본인 매장 메뉴) | 204 |
| 주문 생성 | POST | `/api/orders` | O | CUSTOMER | 201 |
| 주문 목록 조회 | GET | `/api/orders?page=&size=` | O | CUSTOMER / OWNER | 200 |
| 주문 취소 (결제 취소 포함) | PATCH | `/api/orders/{id}/cancel` | O | CUSTOMER (본인 주문) | 204 |
| 주문 상태 변경 | PATCH | `/api/orders/{id}/status` | O | OWNER (본인 매장 주문) | 204 |
| 결제 | POST | `/api/payments` | O | CUSTOMER (본인 주문) | 201 |
| 정산 목록 조회 (관리자) | GET | `/api/admin/settlements?date=&status=` | O | ADMIN | 200 |
| 정산 확정 (관리자) | PATCH | `/api/admin/settlements/{id}/confirm` | O | ADMIN | 200 |
| 정산 지급 (관리자) | PATCH | `/api/admin/settlements/{id}/pay` | O | ADMIN | 200 |
| 정산 상태 변경 이력 (관리자) | GET | `/api/admin/settlements/{id}/histories` | O | ADMIN | 200 |
| 정산 재집계 (관리자) | POST | `/api/admin/settlements/reaggregate?date=&storeId=` | O | ADMIN | 200 |
| 정산 집계 실패 기록 (관리자) | GET | `/api/admin/settlements/aggregation-failures?status=` | O | ADMIN | 200 |
| 결제 환불 (관리자) | PATCH | `/api/admin/payments/{id}/refund` | O | ADMIN | 200 |
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
{ "username": "user1", "password": "password123", "role": "CUSTOMER" }
```
- `username`: 4~20자
- `password`: 8~72자 (BCrypt로 암호화해 저장)
- `role`: `CUSTOMER` 또는 `OWNER` (`ADMIN`은 가입 불가 → 400, 관리자는 DB에서 직접 지정)

Response `201 Created`
```json
{ "id": 1, "username": "user1", "role": "CUSTOMER" }
```

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패, role이 CUSTOMER/OWNER가 아님 (`ADMIN` 포함) |
| 409 | 이미 사용 중인 아이디 |

### 로그인 `POST /api/users/login`
Request
```json
{ "username": "user1", "password": "password123" }
```
Response `200 OK`
```json
{ "accessToken": "eyJhbGciOi...", "tokenType": "Bearer" }
```
- 토큰 유효 시간: 1시간

| 코드 | 상황 |
|---|---|
| 400 | 아이디/비밀번호 누락 |
| 401 | 아이디 또는 비밀번호 불일치 (어느 쪽이 틀렸는지 구분하지 않음) |

### 내 토큰 정보 조회 `GET /api/users/me`
Response `200 OK`
```json
{ "username": "user1", "authorities": ["ROLE_CUSTOMER"] }
```
- 토큰 인증 확인용. DB를 조회하지 않고 **토큰에 담긴** 아이디·역할을 그대로 돌려줌 → 역할이 DB에서 바뀌어도 토큰이 만료되기 전까지는 이전 역할이 보임 (실제 권한 판단은 각 Service가 DB 기준으로 다시 확인)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음·만료·위조 |

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
  "deliveryAddress": "서울시 강남구 ..."
}
```
- `items`: 필수, 1~50개. 모두 같은 매장의 메뉴여야 함
  - `menuId`: 필수, 같은 메뉴는 한 항목으로 합쳐서 요청 (중복 시 400)
  - `quantity`: 1 이상
- `deliveryAddress`: 필수, 255자 이하
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
  "deliveryAddress": "서울시 강남구 ...", "status": "ORDER_REQUEST",
  "createdAt": "2026-10-08T14:03:00", "updatedAt": "2026-10-08T14:03:00",
  "deliveredAt": null
}
```
- `menuName`, `unitPrice`는 현재 메뉴가 아닌 주문 당시 값
- `deliveredAt`: 배달완료 시각, 배달완료 전에는 `null`

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패, 같은 메뉴 중복, 다른 매장의 메뉴가 섞임, 총액이 너무 큼(long 범위 초과) |
| 401 | 토큰 없음 |
| 403 | OWNER가 요청 |
| 404 | 항목 중 하나라도 메뉴가 없거나 삭제됨 |

### 주문 목록 조회 `GET /api/orders`
Response `200 OK` — 주문 생성 응답 형식의 배열 ([페이지네이션](#공통-사항) `?page=&size=`)
- CUSTOMER: 본인이 한 주문만
- OWNER: 본인의 모든 매장에 들어온 주문만 (매장 하나만 보려면 `GET /api/stores/{id}/orders`)

| 코드 | 상황 |
|---|---|
| 400 | `page`·`size` 범위 초과 |
| 401 | 토큰 없음 |

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
| 409 | 주문수락 이후(`ORDER_ACCEPTED`, `DELIVERY_COMPLETED`)이거나 이미 취소됨 — 배달완료 후에는 관리자 환불 |

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
| 409 | 허용되지 않는 변경 (결제 전 주문 수락, 단계 건너뛰기, 배달완료 이후 변경 등) |

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
  - 보관 기간: 결제 **완료 시점부터 24시간**. 지난 뒤 같은 키로 보내면 새 요청으로 처리되어, 이미 결제된 주문이므로 409
  - 처리 중 기록은 최대 1분 — 그 사이 같은 키 요청은 409(처리 중), 1분 안에 끝나지 않은 결제는 처음 응답을 보관하지 못해 이후 재시도는 409
- 결제가 실패한 요청(4xx·5xx)은 기록하지 않음 → 같은 키로 다시 시도 가능 (같은 이유면 같은 오류)
- 키와 관계없이 **주문당 결제는 1건** — 다른 키로 이미 결제된 주문을 결제하면 기존처럼 409
- 저장소(Redis)를 쓸 수 없으면 결제하지 않고 503 → 잠시 뒤 같은 키로 재시도 (키 없는 요청은 영향 없음)
  - 결제가 이미 끝난 키라면 재시도 결과는 처음 응답(재전송) 또는 409(이미 결제됨)이고 결제가 다시 되지는 않음
  - 409를 받은 클라이언트는 주문 조회로 결제 상태(`PAYMENT_COMPLETED`)를 확인
- TTL·장애 시 동작 상세는 [프로젝트 구조 — Redis](architecture.md#redis-41)

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 누락, 결제 수단이 CARD가 아님, `Idempotency-Key` 형식 오류 |
| 401 | 토큰 없음 |
| 403 | OWNER가 요청, 본인 주문이 아님 |
| 404 | 주문 없음 |
| 409 | 주문요청 상태가 아님 (이미 결제됨·취소됨), 중복 결제, 같은 `Idempotency-Key`의 결제가 처리 중 |
| 422 | 같은 `Idempotency-Key`로 다른 결제 요청 (`orderId`·`paymentMethod`가 처음과 다름) |
| 503 | `Idempotency-Key`가 있는데 Redis 장애·타임아웃 (결제하지 않음) |

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
배달완료 주문의 결제를 **전액** 환불 — `COMPLETED` → `REFUNDED`, 환불 시각(`refundedAt`)을 서버 시각으로 기록. 요청 본문 없음
- 고객 결제액·플랫폼 수수료·매장 정산액이 모두 취소됨 (결제에 저장된 금액 그대로)
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
