# 정산 기준

[← README](../README.md)

사장님(OWNER)에게 지급할 금액을 **언제, 어떤 주문을, 어느 기간으로 묶어** 계산하는지 정의한다.
건별 수수료·정산액 계산 방식은 [금액 처리 정책](money-policy.md), API는 [매장별 정산 조회](api.md#매장별-정산-조회-get-apistoresidsettlements) 참고.

## 1. 요약

| 항목 | 기준 |
|---|---|
| 정산 단위 | **매장(store)** — 한 OWNER가 여러 매장을 가지면 매장마다 따로 정산 |
| 정산 대상 | 결제(`payments`)가 있고 주문 상태가 **`DELIVERY_COMPLETED`(배달완료)** 인 주문 |
| 정산 대상 시점 | **배달완료 시각** (`orders.delivered_at`, V6) |
| 정산 주기 | **일 정산** — 하루치를 다음 날 확정 (D+1) |
| 기간 기준 | **Asia/Seoul**, 반열림 구간 `[D 00:00, D+1 00:00)` |
| 정산 금액 | 대상 결제의 `settlement_amount` 합계 (수수료는 `fee_amount` 합계) |

## 2. 정산 대상 시점 — 왜 배달완료인가

주문 상태 흐름 중 어느 시점의 결제를 "정산해도 되는 매출"로 볼지 정해야 함.

| 후보 | 장점 | 문제 |
|---|---|---|
| 결제 시각 (`paid_at`) | 이미 저장된 값, 변경되지 않음 | 결제 후 매장이 수락하지 않거나 배달되지 않은 주문까지 정산됨 |
| 주문 수락 | 매장이 주문을 받아들인 시점 | 아직 배달 전이라 이후 문제가 생기면 이미 지급한 금액을 되돌려야 함 |
| **배달완료 (선택)** | 서비스 제공이 끝난 시점 → 이후 상태 변경이 없음(최종 상태) | 배달완료 시각을 저장하는 컬럼이 따로 필요 → V6에서 추가 |

- `DELIVERY_COMPLETED`는 최종 상태라 이후 어떤 전이도 불가 (`Order.validateNotDelivered`) → 한 번 정산 대상이 되면 빠지지 않음
- `ORDER_CANCELLED`는 주문수락 전에만 가능 → 취소된 주문은 배달완료된 적이 없어 정산 대상이 된 적이 없음 (결제완료 주문을 취소하면 결제는 `CANCELLED`)
- 배달완료 후 환불(`REFUNDED`)은 판매분에서 빼지 않고, 환불 이후 집계되는 정산에서 따로 차감 ([9장](#9-결제-취소환불과-차기-정산-반영))
- `PAYMENT_COMPLETED`, `ORDER_ACCEPTED` 상태 결제는 **미확정** → 배달완료되는 날의 정산에 포함

### 배달완료 시각을 따로 저장하는 이유

`orders.updated_at`은 "마지막으로 수정된 시각"이라 배달완료 시각과 지금은 같지만 의미가 다름.
나중에 배달완료 이후에도 바뀌는 컬럼(예: 메모, 리뷰 여부)이 생기면 `updated_at`이 움직여 **이미 정산한 주문이 다른 기간으로 옮겨갈 수 있음**.
`paid_at`을 `created_at`과 분리한 것과 같은 이유로, 업무 데이터인 배달완료 시각은 별도 컬럼으로 둠.

`orders.delivered_at` (`timestamp(6)`, V6)

- **기록**: `Order.complete(deliveredAt)`에서 `Clock` 기준 현재 시각으로 한 번만 기록, 이후 변경 불가. 배달완료 전 주문은 NULL
- **DB 제약**: "배달완료 상태 ⇔ `delivered_at` 있음"을 CHECK 제약(`orders_delivered_at_check`)으로 강제 → 상태와 시각이 어긋난 행이 정산에 섞이지 않음
- **인덱스**: `(store_id, delivered_at)` — 매장별 기간 조회용 (같은 V6에 매장별 상태 조회용 `(store_id, status)`도 추가)
- **기존 데이터 백필**: 마이그레이션 시점에 이미 `DELIVERY_COMPLETED`인 주문은 `updated_at`으로 채움 (배달완료 이후 상태 변경이 불가능해 `updated_at`이 곧 배달완료 시각). `updated_at`이 비어 있는 오래된 행은 `created_at`으로 대신 채움

## 3. 정산 주기 — 일 정산 (D+1)

- 하루(D)에 배달완료된 결제를 묶어 **다음 날(D+1)** 매장별로 확정
- 주간·월간 정산액은 일 정산 결과를 합산해 조회 (주기를 바꿔도 일 단위 데이터는 그대로 사용 가능)
- 주 단위·월 단위가 아닌 일 단위를 고른 이유
  - 가장 작은 단위로 확정해 두면 다른 주기는 합산으로 만들 수 있지만, 큰 단위에서 작은 단위로는 나눌 수 없음
  - 확정 전에 기다리는 기간이 짧아 문제를 일찍 발견할 수 있음

### 확정 시각

- D일 정산은 **D+1 01:00 (Asia/Seoul)** 이후에 확정
- 00:00 직후가 아니라 1시간 여유를 두는 이유: 23:59:59에 시작된 배달완료 트랜잭션이 00:00 이후에 커밋되면, `delivered_at`은 D일이지만 00:00에 집계할 때는 아직 보이지 않음 → 진행 중인 트랜잭션이 끝날 시간을 둠
- 확정된 기간에는 이후 새 건이 들어오지 않음 (배달완료 시각은 항상 커밋 시각보다 앞서고, 확정 시각은 그보다 충분히 뒤)

## 4. 기간 기준

- **시간대**: Asia/Seoul — DB에 저장되는 모든 시각과 같은 기준 ([데이터베이스 · 시간대](database.md#시간대) 참고)
- **구간**: 시작 포함, 끝 미포함 `[start, end)`
  - D일 = `D 00:00:00 ≤ delivered_at < D+1 00:00:00`
  - `BETWEEN D 00:00:00 AND D 23:59:59`처럼 끝을 포함하면 `23:59:59.5` 같은 소수점 초가 빠지고, 다음 날과 이어 붙일 때 경계가 겹치거나 빔
  - 반열림 구간이면 기간을 이어 붙여도 모든 시각이 정확히 한 기간에만 속함
- **조회 기간도 같은 규칙**: 날짜 범위 `from ~ to`(둘 다 포함하는 날짜)로 받으면 내부에서는 `[from 00:00, to+1 00:00)`으로 변환
- **조회 제한** (위반 시 400)
  - `from ≤ to`
  - 한 번에 최대 31일 (건별 내역이 함께 나가므로 응답 크기 제한)
  - 확정된 날짜까지만: `to`는 지금이 01:00 이후면 어제, 01:00 전이면 그저께까지 → 한 번 조회한 정산 금액은 바뀌지 않음

```
       D-1                    D                     D+1
──────────────┬──────────────────────────────┬──────────────────────
              │◀─────── D일 정산 대상 ───────▶│      01:00 D일 확정
            00:00 (포함)                  00:00 (미포함)
```

## 5. 정산 금액

매장 S의 `from ~ to` 정산 대상 결제 (`PaymentRepository.findSettlementTargets`, 실제 쿼리는 JPQL):

```sql
SELECT p.*, o.*
FROM payments p
JOIN orders o ON o.id = p.order_id
WHERE o.store_id = :storeId
  AND o.status = 'DELIVERY_COMPLETED'
  AND o.delivered_at >= :start   -- from 00:00 (포함)
  AND o.delivered_at <  :end     -- to+1 00:00 (미포함)
ORDER BY o.delivered_at, p.id;
```

건별 내역을 함께 응답하므로 결제를 건별로 가져와 애플리케이션에서 합산 (`StoreSettlementResponse.of`):

| 항목 | 계산 |
|---|---|
| `paymentCount` | 건수 |
| `totalAmount` | `amount` 합계 (결제 총액) |
| `totalFee` | `fee_amount` 합계 (플랫폼 수수료) |
| `settlementAmount` | `settlement_amount` 합계 (사장님 지급액) |

- 금액은 결제마다 이미 계산해 저장한 `fee_amount`, `settlement_amount`를 **더하기만 함** → 기간 합계에서 다시 반올림하지 않음
  - 건별로 `수수료 + 정산액 = 결제액`이 보장되므로 합계에서도 `total_fee + settlement_amount = total_amount`
- 수수료율은 결제 시점 스냅샷(`fee_rate`)을 따름 → 같은 기간 안에서 수수료율이 달라도 그대로 합산
- V3 이전 결제(수수료 0, 정산액 = 결제액)도 같은 규칙으로 포함
- 합계는 `Math.addExact`로 더함 → `long` 범위를 넘으면 잘못된 합계를 내보내지 않고 예외

## 6. 이후 확장 시 고려할 점

| 상황 | 영향 |
|---|---|
| 배달완료 이후 환불 | **구현됨 (9장)** — 이미 만들어진 정산은 고치지 않고 환불 이후 처음 집계되는 정산에서 차감, 모자라면 다음 정산으로 이월 |
| 부분 취소 | 미지원 (전액만). 도입 시 결제 `@ManyToOne` 전환 또는 환불 금액 컬럼 필요 |
| 결제 `@ManyToOne` 전환 ([데이터베이스 · 결제 연관관계](database.md#결제-연관관계-onetoone-vs-manytoone)) | 집계 조건에 `p.status = 'SUCCESS'` 추가 (`findSettlementTargets`, `findSettlementStoreIds`) |
| 매장이 많아져 전체 매장 대상 조회가 느려짐 | `findSettlementStoreIds`는 매장 조건 없이 `delivered_at` 구간으로 찾으므로 필요하면 `orders(delivered_at)` 인덱스 추가 검토 |
| 주간·월간 지급 | 일 정산 확정 결과를 합산. 주 시작 요일(월요일 등)은 지급 정책을 정할 때 결정 |
| 매장별 수수료율 | **구현됨 (V10, #32)** — 결제 시점의 매장 수수료율(없으면 공통)이 `fee_rate` 스냅샷으로 저장되므로 정산 기준 변경 없음. 결제 후 배달완료 전에 바뀌어도 결제 시점 수수료율 적용 ([금액 처리 정책](money-policy.md)) |

## 7. 구현 위치

| 규칙 | 위치 |
|---|---|
| 배달완료 시각 기록 | `Order.complete(deliveredAt)` ← `OrderService.statusOrder`가 `Clock` 기준 시각 전달 |
| 기간 변환·조회 제한 (반열림 구간, 확정 시각, 최대 31일) | `entity.SettlementPeriod` |
| 권한·소유권 확인, 조회 흐름 | `SettlementService.getStoreSettlement` |
| 정산 대상 결제 조회 | `PaymentRepository.findSettlementTargets` |
| 합계 계산 | `StoreSettlementResponse.of` |
| DB 컬럼·제약·인덱스 | `V6__add_delivered_at_to_orders.sql` |
| 합계 계산 (조회·집계 공통) | `entity.SettlementTotals` |
| 일 정산 기록·상태 전이 (PENDING → CONFIRMED → PAID) | `entity.Settlement`, `entity.SettlementStatus` |
| 매장별 집계, 중복 정산 방지 | `SettlementAggregationService.aggregateDaily` |
| 정산 대상 매장 조회 | `PaymentRepository.findSettlementStoreIds` |
| 자동 집계 | `scheduler.SettlementScheduler` (활성화: `config.SchedulingConfig`) |
| 관리자 확정·지급, 관리자 확인, 행 잠금 | `AdminSettlementService` ← `AdminSettlementController` |
| 상태 변경 이력 (처리자·시각) | `entity.SettlementStatusHistory` — 집계는 `SettlementAggregationService`, 확정·지급은 `AdminSettlementService`가 저장 |
| 집계 실패 기록, 누락·실패분 재집계 | `entity.SettlementAggregationFailure`, `SettlementAggregationService.reaggregate` ← `AdminSettlementService.reaggregate` |
| 관리자 URL 권한 | `SecurityConfig` (`/api/admin/**`) |
| 정산 테이블·제약 | `V7__add_settlements.sql`, `V8__add_settlement_pending_status_and_admin_role.sql`, `V11__add_settlement_status_histories_and_aggregation_failures.sql` |

## 8. 정산 집계·확정·지급 — settlements

조회 API는 요청할 때마다 결제를 다시 합산하지만, 사장님에게 지급할 금액은 **기록으로 남기고 관리자가 확인한 뒤 지급**해야 지급·대사의 근거가 됨.
매일 스케줄러가 전날(D) 정산을 매장별로 집계해 `settlements`에 **확정 대기(PENDING)** 로 저장하고, 관리자가 확정·지급한다.

### 상태

```
PENDING(확정 대기) ── confirm(confirmedAt) ──▶ CONFIRMED(확정) ── markPaid(paidAt) ──▶ PAID(지급완료)
  스케줄러 집계              관리자 PATCH .../confirm             관리자 PATCH .../pay
```

| 상태 | 의미 | `confirmed_at` | `paid_at` |
|---|---|---|---|
| `PENDING` | 스케줄러가 집계해 금액을 고정, 관리자 확인 대기 | 없음 | 없음 |
| `CONFIRMED` | 관리자가 금액을 확인해 확정, 지급 대상 | 있음 | 없음 |
| `PAID` | 지급까지 끝난 최종 상태 | 있음 | 있음 |

- 전이는 위 두 개뿐. 단계 건너뛰기(`PENDING → PAID`), 되돌리기, 같은 전이 반복은 모두 거부 → `InvalidSettlementStatusException` (409)
  - 이미 지급된 정산을 다시 지급하면 409, 지급 시각은 처음 값 그대로 유지 (중복 지급 방지)
- 전이 규칙은 `Settlement.confirm()`, `Settlement.markPaid()`에서만 판단 (주문의 `Order` 상태 전이와 같은 방식)
- 확정·지급 시각은 서버 `Clock`(Asia/Seoul) 기준 현재 시각. 요청으로 받지 않음
  - 지급 시각이 확정 시각보다 앞서면 거부
- DB에서도 CHECK 제약으로 상태와 시각이 어긋난 행을 거부
  - `settlements_confirmed_at_check`: `PENDING` ⇔ `confirmed_at` 없음 (V8)
  - `settlements_paid_at_check`: `PAID` ⇔ `paid_at` 있음 (V7)
- 집계 금액은 상태가 바뀌어도 변하지 않음. 사장님 정산 조회 API(`GET /api/stores/{id}/settlements`)는 `settlements`를 쓰지 않고 결제를 직접 합산하므로 상태 변경과 무관

### 관리자 권한

| 단계 | 방법 |
|---|---|
| 1. URL | `SecurityConfig` — `/api/admin/**`은 `ADMIN`만 (토큰 없음 401, CUSTOMER·OWNER 403) |
| 2. Service | `AdminSettlementService` — 토큰 발급 후 역할이 바뀐 경우에 대비해 DB의 현재 역할로 한 번 더 확인 (403) |

- `ADMIN`은 회원가입으로 만들 수 없음 (`role: ADMIN` 가입 요청은 400) → 누구나 관리자로 가입해 지급 권한을 얻는 것을 막음
- 관리자 지정은 운영자가 DB에서 직접: `UPDATE users SET role = 'ADMIN' WHERE username = '...';` (역할은 토큰에도 담기므로 다시 로그인해야 반영)

### 동시 요청 — 중복 지급 방지

- 확정·지급할 정산은 행 잠금(`SELECT ... FOR UPDATE`, `SettlementRepository.findByIdForUpdate`)으로 조회
- 같은 정산에 지급 요청이 동시에 여러 번 들어와도 뒤 요청은 앞 요청이 커밋될 때까지 기다린 뒤 `PAID` 상태를 읽고 409 → 지급은 한 번만 성공 (`AdminSettlementIntegrationTest`로 확인)
- 잠금 없이 조회하면 두 요청이 모두 `CONFIRMED`를 읽고 통과해 지급 시각이 덮어써질 수 있음

### 집계 규칙

| 항목 | 기준 |
|---|---|
| 단위 | 매장 1곳 × 하루(D) = 1건 (`settlement_date`) |
| 집계 가능 시점 | D+1 01:00 이후 (조회 API의 확정 시각과 같은 규칙, `SettlementPeriod`) — 이전이면 거부 |
| 대상 | 조회 API와 같은 쿼리(`findSettlementTargets`)로 가져온 결제 |
| 금액 | 조회 API와 같은 합계 계산(`SettlementTotals`) → 집계 금액과 조회 금액이 항상 같음 |
| 대상 없는 매장 | 기록을 만들지 않음 |
| 집계 후 | 금액 변경 불가. 집계 이후 해당 날짜에 새 건이 들어오지 않으므로(3장 확정 시각) 다시 집계할 일이 없음 — 관리자 재집계도 정산이 없는 매장·날짜만 대상 ([10장](#10-감사-이력과-누락실패분-재집계-39)) |

### 중복 정산 방지

| 단계 | 방법 |
|---|---|
| 1. 애플리케이션 | 집계 전에 `(store_id, settlement_date)`가 이미 있는지 확인 → 있으면 상태와 관계없이 건너뜀 (확정·지급된 정산도 다시 만들지 않음) |
| 2. DB | `UNIQUE (store_id, settlement_date)` — 두 실행이 동시에 1을 통과해도 두 번째 INSERT는 거부 → 이미 있는 것을 확인하고 건너뜀 |

- 2의 UNIQUE 위반이 아닌 다른 제약 위반(금액 CHECK 등)은 건너뛰지 않고 실패로 기록
- 여러 서버에서 스케줄러가 함께 돌아도 결과는 같음 (분산 락 없이 DB 제약으로 보장)

### 트랜잭션

- 집계: 매장 1곳 = 트랜잭션 1개 → 한 매장에서 실패해도 다른 매장의 집계는 커밋됨. 실패한 매장은 정산이 남지 않으므로 다음 실행에서 다시 시도되고, 실패 사실은 별도 트랜잭션으로 기록됨 (10장)
- 확정·지급: 요청 1건 = 트랜잭션 1개 (행 잠금은 커밋 시 해제)

### 스케줄러

| 설정 (`application.yml`) | 기본값 | 의미 |
|---|---|---|
| `settlement.scheduler.enabled` | `true` | `false`면 스케줄 실행 안 함 |
| `settlement.scheduler.cron` | `0 10 1 * * *` | 매일 01:10 (Asia/Seoul) — 집계 가능 시각 01:00에 10분 여유 |
| `settlement.scheduler.catch-up-days` | `7` | 실행마다 최근 N일을 오래된 날짜부터 집계 |

- 실행할 때마다 마지막 집계 가능 날짜부터 거슬러 N일을 집계 → 서버가 내려가 실행을 놓친 날도 N일 안이면 다음 실행에서 채워짐
- 이미 정산이 있는 매장·날짜는 건너뛰므로 같은 날짜를 여러 번 처리해도 결과가 같음
- 한 날짜·한 매장의 오류는 로그와 실패 기록(`settlement_aggregation_failures`, 10장)을 남기고 나머지를 계속 처리 → catch-up 기간 안에 해결되지 않으면 관리자가 재집계

### 기존 데이터 (V7 → V8)

- V7 시점에 스케줄러가 만든 정산은 이미 `CONFIRMED`(`confirmed_at` = 집계 시각)로 저장되어 있음 → V8은 그대로 두고 새 제약도 만족
- V8 이후 집계되는 정산부터 `PENDING`으로 시작

## 9. 결제 취소·환불과 차기 정산 반영

### 정책

| 시점 | 처리 | 주체 | 정산 영향 |
|---|---|---|---|
| 주문요청 (`ORDER_REQUEST`) | 주문만 취소 | CUSTOMER | 없음 |
| 결제완료 (`PAYMENT_COMPLETED`) | 주문 `ORDER_CANCELLED` + 결제 `CANCELLED` (전액 취소) | CUSTOMER | 없음 (배달 전) |
| 주문수락 (`ORDER_ACCEPTED`) 이후, 배달 전 | **취소 불가** (409) | - | - |
| 배달완료 (`DELIVERY_COMPLETED`) | 결제 `REFUNDED` (전액 환불), 주문 상태는 그대로 | **ADMIN만** | 다음에 집계되는 정산에서 차감 |

- 환불 시 고객 결제액·플랫폼 수수료·매장 정산액을 모두 취소 → 결제에 저장된 `amount`, `fee_amount`, `settlement_amount`를 그대로 차감
- 상태 전이 규칙은 `Payment.cancel()`, `Payment.refund()`에서만 판단 → 위반 시 `InvalidPaymentStatusException` (409)

### 정산 1건의 구성

```
차감 전 정산액 = 판매 정산액 − 환불 정산액 − 이월받은 금액
지급액(settlement_amount)        = max(차감 전 정산액, 0)
다음 이월액(carried_out_amount)  = max(−차감 전 정산액, 0)
```

| 구성 | 대상 | 연결 기록 |
|---|---|---|
| 판매 | D일에 배달완료된 결제 (나중에 환불돼도 포함 → 이미 조회·집계된 금액이 바뀌지 않음) | - |
| 환불 | `refunded_at < D+1 00:00`이고 **아직 어떤 정산에서도 차감되지 않은** 환불 | `settlement_refunds` (payment_id UNIQUE) |
| 이월 | **D보다 앞 날짜** 정산에서 넘어왔고 아직 차감되지 않은 이월분 | `settlement_carryovers` (from_settlement_id UNIQUE) |

### 지키는 규칙

| 규칙 | 보장 방법 |
|---|---|
| **이미 만들어진 정산은 바뀌지 않음** (PENDING·CONFIRMED·PAID 모두) | 집계는 새 정산과 연결 기록을 INSERT만 함. 기존 정산 행은 UPDATE하지 않음 |
| **환불일 정산이 이미 있으면 다음 정산에서 차감** | 미차감 환불을 날짜 구간이 아니라 "연결 기록이 없는지"로 찾음 → 늦게 커밋된 환불, catch-up 기간을 넘긴 환불도 누락되지 않음 |
| 판매가 없어도 차감 | 미차감 환불이 있는 매장은 집계 대상에 포함 → 판매 0건 정산을 만들어 차감 (보통 다음 날 정산) |
| **중복 차감 없음** | ① 매장 행 잠금으로 같은 매장 집계를 직렬화 ② `settlement_refunds.payment_id`, `settlement_carryovers.from_settlement_id` UNIQUE → 잠금이 빠져도 DB가 거부하고 매장 트랜잭션 전체 롤백 |
| **차감은 원인보다 같거나 늦은 날짜에서만** | 환불: `refunded_at < 정산일+1`, 이월: `from.settlement_date < to.settlement_date` (`SettlementRefund`, `SettlementCarryover` 생성 시 검증) |
| 날짜 역순 집계 (D-1을 D보다 늦게 집계) | D-1은 D의 이월분을 가져가지 않음 → D+1 이후 정산에서 차감. 총 지급액은 집계 순서와 무관 |
| 정산 행 ↔ 연결 기록 일치 | 같은 트랜잭션에서 저장 → 함께 커밋되거나 함께 롤백 |

- 이월분만 남고 판매·환불이 없으면 정산을 만들지 않고, 다음 판매·환불이 생기는 날의 정산에서 차감
- 지급액 0원 정산도 기존과 같이 PENDING → CONFIRMED → PAID를 거침
- 수수료는 사장님 지급액이 아니므로 이월하지 않음 (판매 수수료·환불 수수료를 각각 기록)
- 회계 항등식: `Σ지급액 = Σ판매 정산액 − Σ환불 정산액 + 아직 차감되지 않은 이월분` (`PaymentRefundIntegrationTest`로 검증)

### 집계 순서 (매장 1곳, 날짜 D, 트랜잭션 1개)

```
1. stores 행 잠금 (SELECT ... FOR UPDATE)
2. (store, D) 정산이 이미 있으면 건너뜀
3. 판매 = findSettlementTargets(store, [D, D+1)),  환불 = findUnsettledRefunds(store, D+1)
4. 판매·환불이 모두 없으면 건너뜀
5. 이월 = findUnsettledCarryovers(store, D)
6. Settlement.aggregate(...) → INSERT
7. settlement_refunds, settlement_carryovers INSERT
8. 상태 이력(→ PENDING) INSERT, 이 매장·날짜의 실패 기록이 있으면 RESOLVED (10장)
9. 오류 → 이 매장 트랜잭션 전체 롤백 후 실패 기록, 다음 실행 또는 관리자 재집계에서 재시도
```

집계 대상 매장 = D일 판매가 있는 매장 ∪ `refunded_at < D+1`인 미차감 환불이 있는 매장

### 동시 요청

| 경합 | 처리 |
|---|---|
| 같은 결제 동시 환불 | 결제 행 잠금(`PaymentRepository.findByIdForUpdate`) → 한 번만 성공, 나머지 409 |
| 고객 취소 ↔ 사장님 수락 | 주문 행 잠금(`OrderRepository.findByIdForUpdate`, 결제·취소·상태 변경 공통) → 한쪽만 성공, 주문·결제 상태 일치 |
| 같은 매장의 여러 날짜 동시 집계 | 매장 행 잠금 + 연결 기록 UNIQUE |

### 사장님 조회 API와의 관계

- 사장님 기간 조회(`/settlements`)는 판매를 배달완료 시각, 환불을 **환불 시각** 기준으로 보여줌 (`netSettlementAmount` = 판매 − 환불, 음수 가능) — 발생 기준
- 사장님 일 정산 기록 조회(`/daily-settlements`, `OwnerSettlementService`)는 저장된 정산 기준 — 실제 지급액·이월·상태, 어느 환불이 어느 정산에서 차감됐는지(`settlement_refunds`), 이월분이 어디서 차감됐는지(`settlement_carryovers`)를 보여줌. 관리자 정산과 같은 행을 읽으므로 금액이 항상 같음
  - 예) 11/5 23시 환불이 11/5 정산 집계 뒤에 반영되면 기간 조회에서는 11/5 환불, 일 정산 기록에서는 11/6 정산의 차감으로 보임

## 10. 감사 이력과 누락·실패분 재집계 (#39)

### 정산 상태 변경 이력 — settlement_status_histories (V11)

정산이 만들어지고 상태가 바뀔 때마다 **누가·언제·어떤 상태로** 바꿨는지 1건씩 INSERT한다 (수정·삭제 없음).

| 전이 | 기록 시점 | 처리자(`actor_user_id`) | 시각(`changed_at`) |
|---|---|---|---|
| (없음) → `PENDING` | 집계로 정산 생성 | 스케줄러면 NULL, 수동 재집계면 그 관리자 | 집계 시각 |
| `PENDING` → `CONFIRMED` | 관리자 확정 | 확정한 관리자 (필수) | `confirmed_at`과 같은 값 |
| `CONFIRMED` → `PAID` | 관리자 지급 | 지급한 관리자 (필수) | `paid_at`과 같은 값 |

| 규칙 | 보장 방법 |
|---|---|
| **처리자는 요청 값을 믿지 않음** | API는 처리자 파라미터를 받지 않음. 토큰의 username으로 DB에서 조회해 `ADMIN`임을 확인한 회원(`AdminSettlementService.validateAdmin`)을 그대로 기록 |
| 상태만 바뀌고 이력이 빠지는 일 없음 | 상태 전이와 같은 트랜잭션에서 저장 (집계는 매장 트랜잭션, 확정·지급은 행 잠금을 잡은 요청 트랜잭션) |
| 거부된 전이는 이력을 남기지 않음 | `Settlement.confirm/markPaid`가 409를 던지면 저장 전에 끝남. 이력은 정산의 현재 상태와 같을 때만 생성 가능(`SettlementStatusHistory`) |
| 같은 전이가 두 번 기록되지 않음 | `UNIQUE (settlement_id, to_status)` — 중복 지급 이력은 DB가 거부 |
| 허용된 전이만, 확정·지급은 처리자 필수 | CHECK 제약 (`..._transition_check`, `..._actor_check`) |

- 조회: 관리자 정산 응답의 `confirmedBy`·`paidBy`, 정산별 전체 이력은 `GET /api/admin/settlements/{id}/histories`
- 사장님 응답(`OwnerSettlementResponse`)에는 처리자를 넣지 않음 (관리자 계정 정보 비노출)
- **V11 전에 일어난 전이는 이력이 없음**: 처리자를 알 수 없어 채워 넣지 않음 → 그런 정산은 `confirmedBy`·`paidBy`가 `null` (시각은 기존 `confirmed_at`·`paid_at` 그대로)

### 집계 실패 기록 — settlement_aggregation_failures (V11)

매장 집계가 실패하면 그 매장 트랜잭션은 전부 롤백되어 정산이 남지 않는다. 이전에는 로그만 남아 **어떤 매장·날짜가 빠졌는지 DB에서 알 수 없었고**, catch-up 기간(7일) 안에 원인이 해결되지 않으면 조용히 누락됐다.

```
집계 실패 ──▶ FAILED(정산 없음, 재집계 대상) ── 이후 집계 성공 / 대상 없음 확인 ──▶ RESOLVED
                 ▲                                                          │
                 └────────────── 다시 실패 (failure_count + 1) ◀─────────────┘
```

- 매장·날짜당 1건 (`UNIQUE (store_id, settlement_date)`). 다시 실패하면 같은 행의 횟수·원인·시각만 갱신 (정산 금액과 무관한 운영 기록이라 UPDATE)
- 실패 기록은 집계 트랜잭션이 롤백된 뒤 **별도 트랜잭션**으로 저장. 매장 행 잠금을 잡고 "정산이 아직 없음"을 다시 확인하므로 정산이 있는데 `FAILED`인 기록은 생기지 않음
- 해결은 **성공한 집계와 같은 트랜잭션**에서 처리 → 스케줄러 재실행으로 성공해도, 관리자 재집계로 성공해도 `RESOLVED` (처리자: 스케줄러면 NULL)
- 원인(`last_error`)은 예외 종류와 제약 조건 이름만 저장 (DB 오류 상세에는 입력값이 섞일 수 있음). 전체 스택 트레이스는 서버 로그
- 실패 기록 저장 자체가 실패하면(DB 장애 등) 기존처럼 로그만 남음

### 재집계 — `POST /api/admin/settlements/reaggregate`

**재집계 = 정산이 없는 매장·날짜를 새로 집계하는 것.** 이미 만들어진 정산은 `PENDING`이라도 다시 계산하지 않는다 (8·9장의 "기존 정산 행은 바꾸지 않음" 유지 — 늦게 들어온 환불은 다음 정산에서 차감).

| 대상 | 결과 |
|---|---|
| 정산이 없고 판매·미차감 환불이 있음 (집계 실패, catch-up 기간을 넘긴 누락) | 새 `PENDING` 정산 (`CREATED`) |
| 정산이 없고 집계할 것도 없음 | 만들지 않음 (`NO_TARGET`) |
| 이미 정산이 있음 (`PENDING`·`CONFIRMED`·`PAID`) | 날짜 전체 재집계: 건너뜀(`ALREADY_EXISTS`) / 매장 지정 재집계: **409** |
| 아직 집계 가능 시각(D+1 01:00) 전인 날짜 | 400 |

- 금액·수수료 계산, 환불·이월 차감은 스케줄러와 **같은 코드**(`SettlementAggregationService.aggregateStore`)
- 지급된 정산은 재집계 대상이 될 수 없으므로 재집계로 중복 지급이 생기지 않음

### 동시 실행·중복 요청

| 경합 | 처리 |
|---|---|
| 같은 재집계를 여러 번·동시에 요청 | 매장 행 잠금으로 직렬화 → 첫 요청만 `CREATED`, 나머지는 정산을 확인하고 `ALREADY_EXISTS` |
| 스케줄러 ↔ 수동 재집계 | 같은 잠금·같은 UNIQUE(`store_id, settlement_date`)를 공유 → 정산·집계 이력은 매장·날짜당 1건, 진 쪽은 실패가 아니라 건너뜀 |
| 같은 정산을 두 관리자가 동시에 확정·지급 | 기존 정산 행 잠금 → 한 명만 성공, 이력의 처리자 = 실제로 성공한 관리자 |

- 새 잠금·분산 락을 추가하지 않고 기존 매장 행 잠금과 DB 제약을 그대로 사용 (`SettlementAuditRecoveryIntegrationTest`로 확인)
