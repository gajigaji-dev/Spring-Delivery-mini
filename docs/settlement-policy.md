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
- `ORDER_CANCELLED`는 결제 전에만 가능하므로 결제가 있는 주문이 취소되는 경우는 현재 없음
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
| 배달완료 이후 환불·부분 취소 도입 | 이미 확정된 기간을 고치지 않고, **환불이 발생한 날의 정산에서 차감**(음수 항목)하는 방식으로 처리 |
| 결제 `@ManyToOne` 전환 ([데이터베이스 · 결제 연관관계](database.md#결제-연관관계-onetoone-vs-manytoone)) | 집계 조건에 `p.status = 'SUCCESS'` 추가 (`findSettlementTargets`, `findSettlementStoreIds`) |
| 매장이 많아져 전체 매장 대상 조회가 느려짐 | `findSettlementStoreIds`는 매장 조건 없이 `delivered_at` 구간으로 찾으므로 필요하면 `orders(delivered_at)` 인덱스 추가 검토 |
| 주간·월간 지급 | 일 정산 확정 결과를 합산. 주 시작 요일(월요일 등)은 지급 정책을 정할 때 결정 |
| 매장별 수수료율 | 결제 시점 `fee_rate` 스냅샷을 쓰므로 정산 기준 변경 없음 |

## 7. 구현 위치

| 규칙 | 위치 |
|---|---|
| 배달완료 시각 기록 | `Order.complete(deliveredAt)` ← `OrderService.statusOrder`가 `Clock` 기준 시각 전달 |
| 기간 변환·조회 제한 (반열림 구간, 확정 시각, 최대 31일) | `entity.SettlementPeriod` |
| 권한·소유권 확인, 조회 흐름 | `SettlementService.getStoreSettlement` |
| 정산 대상 결제 조회 | `PaymentRepository.findSettlementTargets` |
| 합계 계산 | `StoreSettlementResponse.of` |
| DB 컬럼·제약·인덱스 | `V6__add_delivered_at_to_orders.sql` |
| 합계 계산 (조회·확정 공통) | `entity.SettlementTotals` |
| 일 정산 확정 기록·상태 전이 | `entity.Settlement`, `entity.SettlementStatus` |
| 매장별 확정, 중복 확정 방지 | `SettlementConfirmService.confirmDaily` |
| 정산 대상 매장 조회 | `PaymentRepository.findSettlementStoreIds` |
| 자동 실행 | `scheduler.SettlementScheduler` (활성화: `config.SchedulingConfig`) |
| 확정 테이블·제약 | `V7__add_settlements.sql` |

## 8. 정산 확정 — settlements

조회 API는 요청할 때마다 결제를 다시 합산하지만, 사장님에게 지급할 금액은 **한 번 확정해 기록으로 남겨야** 지급·대사의 근거가 됨.
매일 스케줄러가 전날(D) 정산을 매장별로 확정해 `settlements`에 저장한다.

### 확정 규칙

| 항목 | 기준 |
|---|---|
| 단위 | 매장 1곳 × 하루(D) = 1건 (`settlement_date`) |
| 확정 가능 시점 | D+1 01:00 이후 (조회 API의 확정 시각과 같은 규칙, `SettlementPeriod`) — 이전이면 거부 |
| 대상 | 조회 API와 같은 쿼리(`findSettlementTargets`)로 가져온 결제 |
| 금액 | 조회 API와 같은 합계 계산(`SettlementTotals`) → 확정 금액과 조회 금액이 항상 같음 |
| 대상 없는 매장 | 기록을 만들지 않음 |
| 확정 후 | 금액 변경 불가. 확정 이후 해당 날짜에 새 건이 들어오지 않으므로(3장 확정 시각) 다시 집계할 일이 없음 |

### 상태

```
CONFIRMED(확정) ── markPaid(paidAt) ──▶ PAID(지급완료)
```

- `CONFIRMED`: 집계해 금액을 고정한 상태. 생성과 동시에 이 상태
- `PAID`: 지급까지 끝난 최종 상태. `CONFIRMED`에서만 전이 가능 (이미 지급된 정산을 다시 지급하지 않음)
- 상태 전이는 `Settlement` 메서드에서만 판단하고, DB에서도 "`PAID` ⇔ `paid_at` 있음"을 CHECK 제약으로 강제
- 지급 처리를 호출하는 API·배치는 아직 없음 (지급 방식이 정해지면 추가)

### 중복 확정 방지

| 단계 | 방법 |
|---|---|
| 1. 애플리케이션 | 확정 전에 `(store_id, settlement_date)`가 이미 있는지 확인 → 있으면 건너뜀 |
| 2. DB | `UNIQUE (store_id, settlement_date)` — 두 실행이 동시에 1을 통과해도 두 번째 INSERT는 거부 → 이미 확정된 것을 확인하고 건너뜀 |

- 2의 UNIQUE 위반이 아닌 다른 제약 위반(금액 CHECK 등)은 건너뛰지 않고 실패로 기록
- 여러 서버에서 스케줄러가 함께 돌아도 결과는 같음 (분산 락 없이 DB 제약으로 보장)

### 트랜잭션

- 매장 1곳 = 트랜잭션 1개 → 한 매장에서 실패해도 다른 매장의 확정은 커밋됨
- 실패한 매장은 기록이 남지 않으므로 다음 실행에서 다시 확정 시도

### 스케줄러

| 설정 (`application.yml`) | 기본값 | 의미 |
|---|---|---|
| `settlement.scheduler.enabled` | `true` | `false`면 스케줄 실행 안 함 |
| `settlement.scheduler.cron` | `0 10 1 * * *` | 매일 01:10 (Asia/Seoul) — 확정 시각 01:00에 10분 여유 |
| `settlement.scheduler.catch-up-days` | `7` | 실행마다 최근 N일을 오래된 날짜부터 확정 |

- 실행할 때마다 마지막 확정 가능 날짜부터 거슬러 N일을 확정 → 서버가 내려가 실행을 놓친 날도 N일 안이면 다음 실행에서 채워짐
- 이미 확정된 매장·날짜는 건너뛰므로 같은 날짜를 여러 번 처리해도 결과가 같음
- 한 날짜·한 매장의 오류는 로그만 남기고 나머지를 계속 처리
