# API 명세

[← README](../README.md)


## 공통 사항
- 인증이 필요한 API는 요청 헤더에 토큰을 담아 보냄
  ```
  Authorization: Bearer {accessToken}
  ```
- 에러 응답은 모두 같은 형식
  ```json
  { "message": "에러 메시지" }
  ```
- 공통 에러 코드

  | 코드 | 상황 |
  |---|---|
  | 400 | 요청 값 검증 실패, JSON 형식 오류 |
  | 401 | 토큰이 없거나 유효하지 않음(만료·위조), 로그인 실패 |
  | 403 | 권한 없음 (역할이 다르거나 본인 데이터가 아님) |
  | 404 | 대상(매장·메뉴·주문)이 없음 |
  | 409 | 중복(아이디, 결제) 또는 현재 상태에서 할 수 없는 요청 |

## API 목록

| 기능 | Method | URL | 인증 | 권한 | 성공 |
|---|---|---|---|---|---|
| 회원가입 | POST | `/api/users/signup` | X | - | 201 |
| 로그인 | POST | `/api/users/login` | X | - | 200 |
| 매장 생성 | POST | `/api/stores` | O | OWNER | 201 |
| 매장 목록 조회 | GET | `/api/stores` | X | - | 200 |
| 매장 단건 조회 | GET | `/api/stores/{id}` | X | - | 200 |
| 매장별 주문 조회 | GET | `/api/stores/{id}/orders` | O | OWNER (본인 매장) | 200 |
| 메뉴 등록 | POST | `/api/menus` | O | OWNER (본인 매장) | 201 |
| 메뉴 목록 조회 | GET | `/api/menus` | X | - | 200 |
| 메뉴 단건 조회 | GET | `/api/menus/{id}` | X | - | 200 |
| 메뉴 수정 | PUT | `/api/menus/{id}` | O | OWNER (본인 매장 메뉴) | 200 |
| 메뉴 삭제 | DELETE | `/api/menus/{id}` | O | OWNER (본인 매장 메뉴) | 204 |
| 주문 생성 | POST | `/api/orders` | O | CUSTOMER | 201 |
| 주문 목록 조회 | GET | `/api/orders` | O | CUSTOMER / OWNER | 200 |
| 주문 취소 | PATCH | `/api/orders/{id}/cancel` | O | CUSTOMER (본인 주문) | 204 |
| 주문 상태 변경 | PATCH | `/api/orders/{id}/status` | O | OWNER (본인 매장 주문) | 204 |
| 결제 | POST | `/api/payments` | O | CUSTOMER (본인 주문) | 201 |

## 주문 상태 흐름

```
ORDER_REQUEST(주문요청) ──결제──▶ PAYMENT_COMPLETED(결제완료) ──OWNER 수락──▶ ORDER_ACCEPTED(주문수락) ──OWNER──▶ DELIVERY_COMPLETED(배달완료)
       │
       └──CUSTOMER 취소──▶ ORDER_CANCELLED(주문취소)
```

| 전이 | `Order` 메서드 | 호출 위치 |
|---|---|---|
| `ORDER_REQUEST → ORDER_CANCELLED` | `cancel()` | `OrderService.cancelOrder` |
| `ORDER_REQUEST → PAYMENT_COMPLETED` | `pay()` | `PaymentService.pay` |
| `PAYMENT_COMPLETED → ORDER_ACCEPTED` | `accept()` | `OrderService.statusOrder` |
| `ORDER_ACCEPTED → DELIVERY_COMPLETED` | `complete()` | `OrderService.statusOrder` |

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
- `role`: `CUSTOMER` 또는 `OWNER`

Response `201 Created`
```json
{ "id": 1, "username": "user1", "role": "CUSTOMER" }
```

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패, role이 CUSTOMER/OWNER가 아님 |
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
Response `200 OK` — 매장 생성 응답 형식 (목록은 배열)

| 코드 | 상황 |
|---|---|
| 404 | 매장 없음 (단건 조회) |

### 매장별 주문 조회 `GET /api/stores/{id}/orders`
Response `200 OK` — 주문 생성 응답 형식의 배열, 해당 매장에 들어온 주문만

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | CUSTOMER가 요청, 본인 매장이 아님 |
| 404 | 매장 없음 |

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
Response `200 OK` — 삭제되지 않은 메뉴만 반환
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
  "createdAt": "2026-10-08T14:03:00", "updatedAt": "2026-10-08T14:03:00"
}
```
- `menuName`, `unitPrice`는 현재 메뉴가 아닌 주문 당시 값

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 검증 실패, 같은 메뉴 중복, 다른 매장의 메뉴가 섞임, 총액이 너무 큼(long 범위 초과) |
| 401 | 토큰 없음 |
| 403 | OWNER가 요청 |
| 404 | 항목 중 하나라도 메뉴가 없거나 삭제됨 |

### 주문 목록 조회 `GET /api/orders`
Response `200 OK` — 주문 생성 응답 형식의 배열
- CUSTOMER: 본인이 한 주문만
- OWNER: 본인의 모든 매장에 들어온 주문만 (매장 하나만 보려면 `GET /api/stores/{id}/orders`)

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |

### 주문 취소 `PATCH /api/orders/{id}/cancel`
Response `204 No Content` — 주문 상태를 `ORDER_CANCELLED`로 변경

| 코드 | 상황 |
|---|---|
| 401 | 토큰 없음 |
| 403 | OWNER가 요청, 본인 주문이 아님 |
| 404 | 주문 없음 |
| 409 | 주문요청 상태가 아님 (결제 완료된 주문 등은 취소 불가) |

### 주문 상태 변경 `PATCH /api/orders/{id}/status`
Request
```json
{ "status": "ORDER_ACCEPTED" }
```
- 허용되는 변경: `PAYMENT_COMPLETED → ORDER_ACCEPTED`, `ORDER_ACCEPTED → DELIVERY_COMPLETED`

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
  "orderStatus": "PAYMENT_COMPLETED", "paidAt": "2026-10-08T14:05:00"
}
```

| 코드 | 상황 |
|---|---|
| 400 | 입력 값 누락, 결제 수단이 CARD가 아님 |
| 401 | 토큰 없음 |
| 403 | OWNER가 요청, 본인 주문이 아님 |
| 404 | 주문 없음 |
| 409 | 주문요청 상태가 아님 (이미 결제됨·취소됨), 중복 결제 |
