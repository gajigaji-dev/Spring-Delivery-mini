# ERD

[← README](../README.md) · [데이터베이스 상세](database.md)

V14 기준. 컬럼 상세·CHECK 제약은 [데이터베이스 · 테이블 상세](database.md#테이블-상세) 참고 (`created_at`·`updated_at`은 생략)

```mermaid
erDiagram
    users ||--o{ stores : "owner_id"
    users ||--o{ orders : "user_id"
    stores ||--o{ menus : "store_id"
    stores ||--o{ orders : "store_id"
    stores ||--o{ settlements : "store_id"
    orders ||--|{ order_items : "order_id (CASCADE)"
    menus ||--o{ order_items : "menu_id"
    orders ||--o| payments : "order_id (UNIQUE)"
    settlements ||--o{ settlement_refunds : "settlement_id"
    payments ||--o| settlement_refunds : "payment_id (UNIQUE)"
    settlements ||--o| settlement_carryovers : "from_settlement_id (UNIQUE)"
    settlements ||--o{ settlement_carryovers : "to_settlement_id"
    settlements ||--o{ settlement_status_histories : "settlement_id"
    users ||--o{ settlement_status_histories : "actor_user_id (NULL = 스케줄러)"
    stores ||--o{ settlement_aggregation_failures : "store_id"
    settlements ||--o| settlement_aggregation_failures : "settlement_id (해결 시)"
    users ||--o{ payment_idempotency_keys : "user_id"
    payments ||--o| payment_idempotency_keys : "payment_id (UNIQUE)"
    users ||--o{ refresh_tokens : "user_id"
    users ||--o{ owner_applications : "user_id (신청자)"
    users ||--o{ owner_applications : "decided_by (처리 관리자)"

    users {
        bigint id PK
        varchar username UK
        varchar password
        varchar role "CUSTOMER/OWNER/ADMIN"
    }
    stores {
        bigint id PK
        bigint owner_id FK
        varchar name
        numeric fee_rate "NULL = 공통 수수료율"
    }
    menus {
        bigint id PK
        bigint store_id FK
        varchar name
        bigint price
        varchar description
        boolean deleted "Soft Delete"
    }
    orders {
        bigint id PK
        bigint user_id FK
        bigint store_id FK
        bigint total_price
        varchar delivery_address "한 줄 주소"
        varchar zip_code "V18, 이전 주문 NULL"
        varchar road_address
        varchar detail_address
        varchar status
        timestamp delivered_at "정산 기간 기준"
    }
    order_items {
        bigint id PK
        bigint order_id FK
        bigint menu_id FK
        varchar menu_name "스냅샷"
        bigint unit_price "스냅샷"
        int quantity
    }
    payments {
        bigint id PK
        bigint order_id FK,UK
        bigint amount
        numeric fee_rate "스냅샷"
        bigint fee_amount
        bigint settlement_amount
        varchar payment_method
        varchar status "COMPLETED/CANCELLED/REFUNDED"
        timestamp paid_at
        timestamp cancelled_at
        timestamp refunded_at
    }
    settlements {
        bigint id PK
        bigint store_id FK
        date settlement_date "store_id와 UNIQUE"
        int payment_count
        bigint total_amount
        bigint total_fee
        bigint sales_settlement_amount
        int refund_count
        bigint refund_amount
        bigint refund_fee
        bigint refund_settlement_amount
        bigint carried_in_amount
        bigint carried_out_amount
        bigint settlement_amount "실제 지급액"
        varchar status "PENDING/CONFIRMED/PAID"
        timestamp confirmed_at
        timestamp paid_at
    }
    settlement_refunds {
        bigint id PK
        bigint settlement_id FK
        bigint payment_id FK,UK
        bigint amount
        bigint fee_amount
        bigint settlement_amount
    }
    settlement_carryovers {
        bigint id PK
        bigint from_settlement_id FK,UK
        bigint to_settlement_id FK
        bigint amount
    }
    settlement_status_histories {
        bigint id PK
        bigint settlement_id FK "to_status와 UNIQUE"
        varchar from_status "NULL = 집계로 생성"
        varchar to_status
        bigint actor_user_id FK "NULL = 스케줄러"
        timestamp changed_at
    }
    settlement_aggregation_failures {
        bigint id PK
        bigint store_id FK
        date settlement_date "store_id와 UNIQUE"
        varchar status "FAILED/RESOLVED"
        int failure_count
        varchar last_error
        timestamp first_failed_at
        timestamp last_failed_at
        bigint last_failed_by_user_id FK
        timestamp resolved_at
        bigint resolved_by_user_id FK
        bigint settlement_id FK
    }
    outbox_events {
        bigint id PK
        uuid event_id UK
        varchar aggregate_type "ORDER"
        bigint aggregate_id "주문 id (FK 없음)"
        varchar event_type
        text payload
        varchar status "PENDING/PUBLISHED/FAILED"
        int attempt_count
        timestamp next_attempt_at
        timestamp published_at
    }
    consumed_events {
        uuid event_id PK
        varchar event_type
        timestamp consumed_at
    }
    notifications {
        bigint id PK
        uuid event_id "recipient_user_id와 UNIQUE"
        varchar event_type
        bigint recipient_user_id "FK 없음"
        bigint order_id "FK 없음"
        varchar message
        varchar send_status "PENDING/SENT/FAILED/SKIPPED"
        int send_attempt_count
        timestamp next_send_at
        timestamp sent_at
        timestamp read_at "NULL = 안 읽음 (V15)"
    }
    payment_idempotency_keys {
        bigint id PK
        bigint user_id FK "idempotency_key와 UNIQUE"
        varchar idempotency_key
        varchar fingerprint "요청 본문 SHA-256"
        bigint payment_id FK "UNIQUE"
        timestamp created_at
    }
    refresh_tokens {
        bigint id PK
        bigint user_id FK
        varchar token_hash "UNIQUE, 원문의 SHA-256"
        uuid family_id "로그인 1번 = 계열 1개"
        timestamp created_at
        timestamp expires_at "<= family_expires_at"
        timestamp family_expires_at "계열 절대 만료 (V16)"
        timestamp revoked_at "사유와 함께 NULL/NOT NULL"
        varchar revoke_reason "ROTATED/LOGOUT/REUSE_DETECTED"
    }
    owner_applications {
        bigint id PK
        bigint user_id FK
        varchar business_name
        varchar status "REQUESTED/APPROVED/REJECTED"
        varchar reject_reason "REJECTED만"
        timestamp requested_at
        timestamp decided_at
        bigint decided_by FK
    }
```

## 테이블 관계

| 관계 | 종류 | FK | 설명 |
|---|---|---|---|
| users ─ stores | 1 : N | `stores.owner_id` → `users.id` | OWNER 한 명이 여러 매장을 보유 |
| stores ─ menus | 1 : N | `menus.store_id` → `stores.id` | 한 매장에 여러 메뉴 |
| stores ─ orders | 1 : N | `orders.store_id` → `stores.id` | 한 매장에 여러 주문이 들어옴 (메뉴의 매장과 항상 같음) |
| users ─ orders | 1 : N | `orders.user_id` → `users.id` | CUSTOMER 한 명이 여러 주문을 생성 |
| orders ─ order_items | 1 : N | `order_items.order_id` → `orders.id` (ON DELETE CASCADE) | 한 주문에 여러 항목, 주문이 지워지면 항목도 함께 삭제 |
| menus ─ order_items | 1 : N | `order_items.menu_id` → `menus.id` | 한 메뉴가 여러 주문 항목에 담김 |
| orders ─ payments | 1 : 1 | `payments.order_id` → `orders.id` (unique) | 주문 1건당 결제는 1건만 가능 |
| stores ─ settlements | 1 : N | `settlements.store_id` → `stores.id` | 한 매장에 날짜별 정산 기록 (매장·날짜당 1건) |
| settlements ─ settlement_refunds | 1 : N | `settlement_refunds.settlement_id` → `settlements.id` | 정산 1건에서 여러 환불을 차감 |
| payments ─ settlement_refunds | 1 : 1 | `settlement_refunds.payment_id` → `payments.id` (unique) | 환불 1건은 정확히 한 정산에서만 차감 |
| settlements ─ settlement_carryovers | 1 : 1 / 1 : N | `from_settlement_id` (unique), `to_settlement_id` → `settlements.id` | 한 정산의 이월분은 한 번만, 한 정산은 여러 이월분을 받을 수 있음 |
| settlements ─ settlement_status_histories | 1 : N | `settlement_status_histories.settlement_id` → `settlements.id` | 정산 1건에 상태 변경 이력 여러 건 (전이마다 1건, 최대 3건) |
| users ─ settlement_status_histories | 1 : N | `settlement_status_histories.actor_user_id` → `users.id` (NULL 허용) | 확정·지급한 관리자. 스케줄러 집계는 NULL |
| stores ─ settlement_aggregation_failures | 1 : N | `settlement_aggregation_failures.store_id` → `stores.id` | 매장·날짜당 집계 실패 기록 1건 |
| settlements ─ settlement_aggregation_failures | 1 : 0..1 | `settlement_aggregation_failures.settlement_id` → `settlements.id` (NULL 허용) | 재집계로 만들어진 정산 |
| users ─ payment_idempotency_keys | 1 : N | `payment_idempotency_keys.user_id` → `users.id` | 회원별 결제 멱등성 키 (회원·키당 1건) |
| payments ─ payment_idempotency_keys | 1 : 0..1 | `payment_idempotency_keys.payment_id` → `payments.id` (unique) | 키가 있는 결제만 기록 1건 (V14) |
| users ─ refresh_tokens | 1 : N | `refresh_tokens.user_id` → `users.id` | 로그인(계열)마다, 재발급마다 1건 (V15). 같은 `family_id` 행끼리가 한 로그인 |
| users ─ owner_applications | 1 : N (신청자), 1 : N (처리자) | `owner_applications.user_id`, `decided_by` → `users.id` | 회원당 여러 번 신청 가능(거절 후 재신청), 대기 중은 1건 (V17 부분 유니크) |

- **Outbox·알림 테이블(V13)은 FK 없음**: `outbox_events`·`consumed_events`·`notifications`는 `event_id`로만 이어지는 이벤트 기록 — 주문·회원 id는 발생 시점 스냅샷 값이라 원본이 지워져도 남음 ([데이터베이스](database.md#outbox_events--발행-대기-이벤트-v13))
- **OWNER가 받은 주문 조회**: `orders → stores → users(owner)`로 본인 매장에 들어온 주문만 찾음 (`findAllByStoreOwnerId`), 매장 하나는 `findAllByStoreId`
- **`orders.store_id`를 따로 두는 이유**: 메뉴를 거치지 않고 매장별 주문 조회·소유권 검증 가능. `Order.addItem`이 다른 매장의 메뉴를 거부하므로 항목 메뉴의 매장과 어긋날 수 없음
- **메뉴는 Soft Delete**: 실제로 행을 지우면 그 메뉴를 참조하는 주문 항목의 FK(`order_items.menu_id`)가 깨지므로, `deleted = true`로만 표시하고 조회에서 제외
- **중복 결제 방지**: `payments.order_id`에 unique 제약을 걸어 같은 주문의 결제가 동시에 들어와도 DB에서 한 건만 저장됨
- **중복 정산 방지**: `settlements (store_id, settlement_date)`에 unique 제약을 걸어 스케줄러가 여러 번·동시에 실행돼도 매장·날짜당 한 건만 저장됨
