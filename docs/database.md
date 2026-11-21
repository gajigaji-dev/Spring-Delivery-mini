# 데이터베이스

[← README](../README.md)

- DB: PostgreSQL
- 스키마는 **Flyway** 마이그레이션(`src/main/resources/db/migration`)으로 관리하고, Hibernate는 `ddl-auto: validate`로 엔티티와 테이블이 맞는지 검사만 함 (맞지 않으면 기동 실패)

## 스키마 마이그레이션 (Flyway)

| 파일 | 내용 |
|---|---|
| `V1__baseline.sql` | Flyway 도입 시점의 스키마 (`ddl-auto: update`로 만들어졌던 테이블·제약조건 이름 그대로) |
| `V2__add_audit_columns_to_users_and_payments.sql` | `users`, `payments`에 `created_at`, `updated_at` 추가 (BaseEntity 상속 반영) |
| `V3__widen_amount_columns_and_add_payment_fee.sql` | 금액 컬럼 `integer` → `bigint`, `payments`에 `fee_rate`, `fee_amount`, `settlement_amount` 추가 (기존 결제는 수수료 0으로 백필) |
| `V4__add_stores.sql` | `stores` 테이블 추가, `menus.store_id`·`orders.store_id` 추가 후 `menus.owner_id` 제거 (메뉴를 가진 점주마다 `{아이디}의 매장`을 1개 만들어 기존 메뉴·주문을 연결) |
| `V5__add_order_items.sql` | `order_items` 테이블 추가, 기존 주문마다 항목 1개로 백필(단가 = `total_price / quantity`) 후 `orders.menu_id`·`orders.quantity` 제거 |
| `V6__add_delivered_at_to_orders.sql` | `orders.delivered_at` 추가, 기존 배달완료 주문은 `updated_at`(없으면 `created_at`)으로 백필, 배달완료 상태 ⇔ 시각 CHECK 제약, `(store_id, delivered_at)`, `(store_id, status)` 인덱스 |
| `V7__add_settlements.sql` | `settlements` 테이블 추가 (매장별 일 정산 확정 기록), `(store_id, settlement_date)` UNIQUE, 상태·지급 시각·금액 CHECK 제약 |
| `V8__add_settlement_pending_status_and_admin_role.sql` | 정산 상태에 `PENDING` 추가, `confirmed_at` NULL 허용 + `PENDING` ⇔ 확정 시각 없음 CHECK, `users.role`에 `ADMIN` 추가 (기존 행은 변경 없음) |
| `V9__add_payment_status_and_settlement_refunds.sql` | `payments`에 `status`·`cancelled_at`·`refunded_at` 추가(기존 결제는 `COMPLETED`), `settlements`에 판매·환불·이월 컬럼 추가(기존 행은 환불·이월 0으로 백필) 및 금액 CHECK 분리, `settlement_refunds`·`settlement_carryovers` 테이블 추가 |
| `V10__add_store_fee_rate.sql` | `stores.fee_rate`(매장별 수수료율, NULL = 공통 수수료율) 추가 + 0~1 CHECK 제약. 기존 매장은 NULL(백필 없음), 기존 결제·정산은 변경 없음 |
| `V11__add_settlement_status_histories_and_aggregation_failures.sql` | `settlement_status_histories`(정산 상태 변경 이력: 처리자·시각, 전이·처리자 CHECK, `(settlement_id, to_status)` UNIQUE), `settlement_aggregation_failures`(집계 실패 기록: `(store_id, settlement_date)` UNIQUE, 상태·해결 정보 CHECK) 테이블 추가. 기존 테이블·행은 변경 없음, 과거 전이 이력은 백필하지 않음 |
| `V12__add_order_list_indexes.sql` | 주문 목록 페이지 조회용 인덱스: `orders (user_id, id)` 추가, `orders (store_id, id)` 추가 후 역할이 겹치는 `idx_orders_store_id (store_id)` 제거. 행은 변경 없음 (근거: [성능 개선 4장](performance.md#4-인덱스-근거-explain)) |
| `V13__add_outbox_events_and_notifications.sql` | Kafka Transactional Outbox (#42): `outbox_events`(발행 대기 이벤트: `event_id` UNIQUE, 상태·종류·발행 시각 CHECK, `PENDING` 부분 인덱스), `consumed_events`(Consumer 처리 기록, `event_id` PK), `notifications`(알림 기록, `(event_id, recipient_user_id)` UNIQUE) 추가. 업무 테이블 FK 없음, 기존 행 변경 없음 |
| `V14__add_idempotency_keys_and_notification_dispatch.sql` | Redis·Kafka 후속 개선: `payment_idempotency_keys`(DB 결제 멱등성 기록, `(user_id, idempotency_key)`·`payment_id` UNIQUE) 추가, `notifications`에 발송 상태 컬럼 추가(기존 알림은 `SKIPPED` — 뒤늦게 발송되지 않음), Outbox FAILED·순서 확인·정리용 인덱스와 `consumed_events`·알림 조회 인덱스 추가. 기존 결제·정산 행 변경 없음 |
| `V15__add_notification_read_at_and_refresh_tokens.sql` | 알림 읽음·Refresh Token: `notifications.read_at`(NULL 허용, 기존 알림은 안 읽음) + 안 읽은 알림 부분 인덱스, `refresh_tokens`(해시 `token_hash` UNIQUE, FK → users, 폐기 시각 ⇔ 사유 CHECK, 유효 행 계열 부분 인덱스) 추가. 기존 행 변경 없음 |
| `V16__add_refresh_token_family_expiry.sql` | Refresh Token 후속: `refresh_tokens.family_expires_at`(계열 절대 만료, NOT NULL, `expires_at <= family_expires_at` CHECK) 추가 — 기존 계열은 첫 발급 + 30일로 백필하되 이미 더 늦게 만료되는 토큰이 있으면 그 시각까지. 정리용 `idx_refresh_tokens_expires_at (expires_at)` 추가 |
| `V17__add_owner_applications.sql` | 사장님 승인제(#70): `owner_applications`(신청자·처리자 FK → users, 상태 CHECK, 처리 시각·처리자 ⇔ 상태 CHECK, 거절 사유 ⇔ REJECTED CHECK) + 대기 중 신청 부분 유니크 `(user_id) WHERE status='REQUESTED'`, 목록용 `(status, id)`, 내 신청용 `(user_id, id)` 인덱스. 기존 OWNER 계정·행 변경 없음 |
| `V18__split_order_delivery_address.sql` | 배송 주소 분리(#77): `orders.zip_code varchar(5)`·`road_address varchar(150)`·`detail_address varchar(100)` 추가(NULL 허용, 기본값 없음 → 기존 행 재작성 없음) + `orders_address_parts_check`(셋 다 NULL이거나, 우편번호 숫자 5자리 + 도로명 있음). 기존 주문은 `delivery_address`만 유지하고 세 컬럼은 NULL(백필 안 함 — 한 줄 주소를 안전하게 나눌 수 없음) |

- **빈 DB**: 애플리케이션 기동 시 V1부터 순서대로 실행
- **Flyway 도입 전부터 쓰던 DB**: `baseline-on-migrate: true`로 V1을 "이미 적용됨"으로 기록하고 V2부터 실행 → 기존 데이터 유지
  - `prod` 프로필은 `false` — 잘못된 DB에 붙었을 때 조용히 진행하지 않고 기동 실패 ([운영 프로필](operations.md))
- **타임아웃**: 마이그레이션도 애플리케이션과 같은 커넥션 풀로 실행되어 `lock_timeout`(5초)·`statement_timeout`(30초)이 적용됨
  - 큰 테이블의 인덱스 생성·백필처럼 오래 걸리는 파일은 맨 위에 `SET LOCAL statement_timeout = 0;` (그 마이그레이션 트랜잭션에만 적용)
- 적용 이력은 `flyway_schema_history` 테이블에 기록됨
- **스키마 변경 방법**: 엔티티를 수정하면 다음 번호의 SQL 파일(`V18__설명.sql`)을 함께 추가
  - 이미 적용된 파일은 수정하지 않음 (체크섬이 달라져 기동 실패) → 수정이 필요하면 새 버전 파일로 추가
  - 제약조건 이름은 V1에서 Hibernate 자동 생성 이름을 그대로 유지했으므로, 이름으로 지울 때 어느 DB든 같은 이름 사용 (예: `payments.order_id` UNIQUE → `uk8vo36cen604as7etdfwmyjsxt`)

> ERD와 테이블 간 관계(FK·카디널리티)는 [ERD](erd.md) 참고

## 생성·수정 시각과 시간대

- **생성·수정 시각 공통화**: `users`, `stores`, `menus`, `orders`, `payments`, `settlements` 모두 `BaseEntity`(@MappedSuperclass)를 상속해 `created_at`, `updated_at`을 JPA Auditing으로 자동 기록
- **시간대** <a id="시간대"></a>: 모든 시각은 `timestamp`(시간대 정보 없음)로 저장되며 **Asia/Seoul 기준** 값임
  - JVM 기본 시간대(`main`, 테스트는 `build.gradle`)와 DB 세션 시간대를 Asia/Seoul로 고정
  - Hibernate `jdbc.time_zone`은 지정하지 않음 → JVM 시간대와 다르면 `LocalDateTime` 저장 시 값이 밀리기 때문 (`application.yml` 주석 참고)
  - 현재 시각은 `LocalDateTime.now()` 대신 `Clock` 빈(`TimeConfig`)에서 얻음 → Auditing(`created_at`, `updated_at`)과 `paid_at`, `delivered_at`이 같은 기준을 쓰고, 테스트에서 `Clock.fixed`로 시각 고정 가능

## 테이블 상세

### users — 회원
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| username | varchar(20) | NOT NULL, UNIQUE | 로그인 아이디 |
| password | varchar(255) | NOT NULL | BCrypt 해시값 |
| role | varchar(255) | NOT NULL | `CUSTOMER` / `OWNER` / `ADMIN`. 가입은 `CUSTOMER`만(V17), `OWNER`는 사장님 전환 승인으로만(`owner_applications`), `ADMIN`은 DB에서 직접 지정 |
| created_at | timestamp | | 가입 시각 (BaseEntity, 이후 변경 불가) |
| updated_at | timestamp | | 마지막 정보 수정 시각 (BaseEntity) |

> `user`는 PostgreSQL 예약어라 테이블명을 `users`로 지정

### stores — 매장
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| owner_id | bigint | FK → users.id, NOT NULL | 매장 주인(OWNER) |
| name | varchar(50) | NOT NULL | 매장 이름 |
| fee_rate | numeric(5,4) | NULL 허용, 0 ~ 1 (CHECK `stores_fee_rate_check`) | 매장별 수수료율 (V10). NULL이면 공통 수수료율(`payment.fee.rate`) 사용. 결제 시점 값이 `payments.fee_rate`에 스냅샷으로 저장됨 |
| created_at | timestamp | | 생성 시각 (BaseEntity, V4 백필 매장은 NULL) |
| updated_at | timestamp | | 마지막 수정 시각 (BaseEntity) |

### menus — 메뉴
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| store_id | bigint | FK → stores.id, NOT NULL | 메뉴를 판매하는 매장 (주인은 `stores.owner_id`) |
| name | varchar(50) | NOT NULL | 메뉴 이름 |
| price | bigint | NOT NULL | 가격 (원) |
| description | varchar(255) | | 설명 (선택) |
| deleted | boolean | | 삭제 여부 (Soft Delete) |
| created_at | timestamp | | 등록 시각 (BaseEntity, 이후 변경 불가) |
| updated_at | timestamp | NOT NULL | 마지막 수정 시각 (BaseEntity) |

### orders — 주문
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| user_id | bigint | FK → users.id, NOT NULL | 주문한 CUSTOMER |
| store_id | bigint | FK → stores.id, NOT NULL | 주문이 들어간 매장 (항목 메뉴들의 매장) |
| total_price | bigint | NOT NULL | 주문 당시 총액 (항목 금액의 합, 서버 계산) |
| delivery_address | varchar(255) | NOT NULL | 한 줄 배송 주소 — 새 주문은 `도로명 + 공백 + 상세`(최대 251자), V18 이전 주문은 이 값만 있음 |
| zip_code | varchar(5) | CHECK 숫자 5자리 | 우편번호 (V18, 이전 주문은 NULL) |
| road_address | varchar(150) | 우편번호가 있으면 NOT NULL (CHECK) | 도로명 주소 + 참고 항목(동·아파트명) (V18) |
| detail_address | varchar(100) | | 상세 주소, 선택 (V18) |
| status | varchar(255) | NOT NULL | `ORDER_REQUEST` / `ORDER_CANCELLED` / `PAYMENT_COMPLETED` / `ORDER_ACCEPTED` / `DELIVERY_COMPLETED` |
| created_at | timestamp | | 주문 시각 (BaseEntity, 이후 변경 불가) |
| updated_at | timestamp | | 마지막 상태 변경 시각 (BaseEntity) |
| delivered_at | timestamp(6) | 배달완료 상태일 때만 NOT NULL (CHECK) | 배달완료 시각 — 정산 기간 기준 ([정산 기준](settlement-policy.md)) |

> `delivered_at`을 `updated_at`과 따로 두는 이유: `updated_at`은 이후 다른 값이 바뀌면 움직여 이미 정산한 주문의 기간이 달라질 수 있음
> `(store_id, delivered_at)` 인덱스로 매장별 정산 기간 조회, `(store_id, status)` 인덱스로 매장별 상태 조회
> `(user_id, id)`, `(store_id, id)` 인덱스로 고객·매장별 주문 목록을 id 순서로 페이지 조회 (V12 — 정렬 없이 필요한 페이지만큼만 읽음)

> 메뉴 가격이 나중에 바뀌어도 주문 금액이 변하지 않도록 `total_price`를 주문 시점에 따로 저장

### order_items — 주문 항목
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| order_id | bigint | FK → orders.id (ON DELETE CASCADE), NOT NULL | 항목이 속한 주문 |
| menu_id | bigint | FK → menus.id, NOT NULL | 주문한 메뉴 |
| menu_name | varchar(50) | NOT NULL | 주문 당시 메뉴 이름 (스냅샷) |
| unit_price | bigint | NOT NULL, 0 이상 | 주문 당시 메뉴 단가 (스냅샷) |
| quantity | integer | NOT NULL, 1 이상 | 수량 |

> `(order_id, menu_id)` UNIQUE → 한 주문 안에서 같은 메뉴는 한 항목으로만 (수량으로 합침)
> 이름·단가를 복사해 두므로 메뉴가 수정·삭제되어도 주문 내역은 주문 당시 그대로. V5 백필 항목의 이름은 당시 이름이 남아 있지 않아 마이그레이션 시점의 메뉴 이름

### payments — 결제
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| order_id | bigint | FK → orders.id, NOT NULL, UNIQUE | 결제한 주문 |
| amount | bigint | NOT NULL | 결제 금액 (주문의 `total_price`를 그대로 저장) |
| fee_rate | numeric(5,4) | NOT NULL, 0 ~ 1 | 결제 시점의 수수료율 스냅샷 (매장 수수료율, 없으면 공통 수수료율. 예: `0.0500`) |
| fee_amount | bigint | NOT NULL | 플랫폼 수수료 (`amount × fee_rate`, 원 단위 HALF_UP) |
| settlement_amount | bigint | NOT NULL | 사장님 정산액 (`amount - fee_amount`) |
| payment_method | varchar(255) | NOT NULL | `CARD` |
| paid_at | timestamp(6) | NOT NULL | 결제 시각 |
| status | varchar(20) | NOT NULL, `COMPLETED` / `CANCELLED` / `REFUNDED` | 결제 상태 (V9, 기존 결제는 `COMPLETED`) |
| cancelled_at | timestamp(6) | `CANCELLED`일 때만 NOT NULL (CHECK) | 배달 전 취소 시각 |
| refunded_at | timestamp(6) | `REFUNDED`일 때만 NOT NULL (CHECK), 부분 인덱스 `WHERE status = 'REFUNDED'` | 배달 후 환불 시각 |
| created_at | timestamp | | 기록 생성 시각 (BaseEntity, 이후 변경 불가) |
| updated_at | timestamp | | 기록 수정 시각 (BaseEntity) |

> `fee_amount >= 0`, `settlement_amount >= 0`, `fee_amount + settlement_amount = amount`, `fee_rate` 0~1을 CHECK 제약으로 DB에서도 강제
> 합계 비교는 `numeric`으로 올려서 계산 → bigint끼리 더할 때의 오버플로(`bigint out of range`) 없이 항상 CHECK 위반으로 거부됨
> 금액 타입·반올림·수수료율 설정은 [금액 처리 정책](money-policy.md), 기간별 정산 집계 기준은 [정산 기준](settlement-policy.md) 참고

> `paid_at`과 `created_at`은 지금은 거의 같은 값이지만 의미가 다름. `created_at`은 DB에 기록된 시각(기술적 기록), `paid_at`은 결제가 승인된 시각(업무 데이터). PG 연동 시 승인 시각을 PG 응답값으로 받으면 두 값이 달라질 수 있어 별도로 유지

### settlements — 일 정산
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| store_id | bigint | FK → stores.id, NOT NULL | 정산 대상 매장 |
| settlement_date | date | NOT NULL, (store_id, settlement_date) UNIQUE | 정산 날짜 — 배달완료 시각이 `[D 00:00, D+1 00:00)`(Asia/Seoul)인 결제 |
| payment_count | integer | NOT NULL, 0 이상 | 판매 건수 (이날 배달완료된 결제) |
| total_amount | bigint | NOT NULL | 판매 결제 총액 |
| total_fee | bigint | NOT NULL, 0 이상 | 판매 플랫폼 수수료 합계 |
| sales_settlement_amount | bigint | NOT NULL, 0 이상 | 판매 정산액 합계 (V9) |
| refund_count, refund_amount, refund_fee, refund_settlement_amount | integer / bigint | NOT NULL, 0 이상 | 이 정산에서 차감한 환불 합계 (V9, `settlement_refunds`의 합과 같음) |
| carried_in_amount | bigint | NOT NULL, 0 이상 | 이전 정산에서 이월받아 차감한 금액 (V9, `settlement_carryovers`의 합과 같음) |
| carried_out_amount | bigint | NOT NULL, 0 이상 | 다음 정산으로 넘긴 금액 (V9) |
| settlement_amount | bigint | NOT NULL, 0 이상 | **실제 지급액** (V9 이전 행은 판매 정산액과 같음) |
| status | varchar(20) | NOT NULL, `PENDING` / `CONFIRMED` / `PAID` | 정산 상태 (확정 대기 → 확정 → 지급완료) |
| confirmed_at | timestamp(6) | `PENDING`이면 NULL, 그 외 NOT NULL (CHECK) | 관리자 확정 시각 |
| paid_at | timestamp(6) | `PAID`일 때만 NOT NULL (CHECK) | 지급 시각 |
| created_at | timestamp(6) | | 집계 시각 (BaseEntity) |
| updated_at | timestamp(6) | | 마지막 상태 변경 시각 (BaseEntity) |

> 금액 규칙을 payments와 같은 방식(`numeric`으로 올려 비교)의 CHECK 제약으로 강제 (V9)
> - 판매: `total_fee + sales_settlement_amount = total_amount` / 환불: `refund_fee + refund_settlement_amount = refund_amount`
> - 지급: `settlement_amount = 0 OR carried_out_amount = 0`
> - 순액: `settlement_amount - carried_out_amount = sales_settlement_amount - refund_settlement_amount - carried_in_amount`
> 정산 대상이 없는 매장·날짜는 행을 만들지 않음 (빈 기록이 매장 수 × 날짜 수만큼 쌓이지 않도록)
> 내 정산 내역 조회(`GET /api/settlements`, #59)는 **인덱스를 추가하지 않음** — 점주 매장을 `idx_stores_owner_id`(V4)로 찾고,
> 정산은 `uk_settlements_store_date (store_id, settlement_date)`의 `store_id` 선두 컬럼으로 매장별로 읽은 뒤 id로 정렬.
> 정산은 매장당 하루 최대 1행(1년 약 365행)이라 점주 한 명의 정산을 모두 정렬해도 작고, `(store_id, id)` 인덱스는 쓰기 비용 대비 이득이 작음.
> (부하 테스트·EXPLAIN 실측은 아님 — 매장 수가 크게 늘면 `docs/performance.md` 방식으로 확인 후 검토)
> 집계·확정·지급과 스케줄러 동작은 [정산 기준 · 정산 집계·확정·지급](settlement-policy.md#8-정산-집계확정지급--settlements) 참고

### settlement_refunds — 환불 차감 기록 (V9)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| settlement_id | bigint | FK → settlements.id, NOT NULL, 인덱스 | 환불을 차감한 정산 |
| payment_id | bigint | FK → payments.id, NOT NULL, **UNIQUE** | 환불된 결제 — 한 번만 차감 |
| amount, fee_amount, settlement_amount | bigint | NOT NULL, `fee_amount + settlement_amount = amount` | 환불 금액 스냅샷 |
| created_at, updated_at | timestamp(6) | | BaseEntity |

### settlement_carryovers — 이월 차감 기록 (V9)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| from_settlement_id | bigint | FK → settlements.id, NOT NULL, **UNIQUE** | 이월분을 넘긴 정산 — 한 번만 차감 |
| to_settlement_id | bigint | FK → settlements.id, NOT NULL, 인덱스 | 이월분을 차감한 정산 |
| amount | bigint | NOT NULL, `> 0` | 차감한 금액 (= 넘긴 정산의 `carried_out_amount`) |
| created_at, updated_at | timestamp(6) | | BaseEntity |

> `from_settlement_id <> to_settlement_id` CHECK. "더 뒤 날짜로만 이월"은 행 간 비교라 애플리케이션(`SettlementCarryover`)에서 검증
> 미차감 = 이 테이블들에 기록이 없는 환불·이월분 → 다음 집계가 가져감 ([정산 기준 9장](settlement-policy.md#9-결제-취소환불과-차기-정산-반영))

### settlement_status_histories — 정산 상태 변경 이력 (V11)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| settlement_id | bigint | FK → settlements.id, NOT NULL, (settlement_id, to_status) **UNIQUE** | 대상 정산 — 같은 전이는 한 번만 기록 |
| from_status | varchar(20) | NULL 허용 | 이전 상태 (집계로 처음 만들어지면 NULL) |
| to_status | varchar(20) | NOT NULL | 바뀐 상태 |
| actor_user_id | bigint | FK → users.id, `to_status`가 `PENDING`이 아니면 NOT NULL (CHECK) | 처리자 — 스케줄러 집계만 NULL |
| changed_at | timestamp(6) | NOT NULL | 처리 시각 (확정·지급은 `confirmed_at`·`paid_at`과 같은 값) |

> 허용 전이만 저장 (CHECK): `NULL → PENDING`, `PENDING → CONFIRMED`, `CONFIRMED → PAID`
> INSERT만 하고 수정하지 않으므로 `BaseEntity`를 상속하지 않음 (`updated_at` 없음). V11 전의 전이는 행이 없음

### settlement_aggregation_failures — 정산 집계 실패 기록 (V11)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| store_id | bigint | FK → stores.id, NOT NULL, (store_id, settlement_date) **UNIQUE** | 집계에 실패한 매장 |
| settlement_date | date | NOT NULL | 집계에 실패한 정산 날짜 |
| status | varchar(20) | NOT NULL, `FAILED` / `RESOLVED` | 실패(재집계 대상) / 해결 |
| failure_count | integer | NOT NULL, 1 이상 | 지금까지 실패한 횟수 |
| last_error | varchar(500) | NOT NULL | 마지막 실패 원인 요약 (예외 종류·제약 조건 이름) |
| first_failed_at, last_failed_at | timestamp(6) | NOT NULL | 처음·마지막 실패 시각 |
| last_failed_by_user_id | bigint | FK → users.id | 마지막 실패를 실행한 관리자 (스케줄러면 NULL) |
| resolved_at | timestamp(6) | `RESOLVED`일 때만 NOT NULL (CHECK) | 해결 시각 |
| resolved_by_user_id | bigint | FK → users.id, `FAILED`면 NULL (CHECK) | 해결한 관리자 (스케줄러면 NULL) |
| settlement_id | bigint | FK → settlements.id, `FAILED`면 NULL (CHECK) | 재집계로 만들어진 정산 (대상 없음으로 해결되면 NULL) |

> 미해결 실패 조회용 부분 인덱스 `idx_settlement_aggregation_failures_failed (settlement_date) WHERE status = 'FAILED'`
> 정산 금액과 무관한 운영 기록이라 같은 행을 갱신함. 기록·해결 규칙은 [정산 기준 10장](settlement-policy.md#10-감사-이력과-누락실패분-재집계-39)

### outbox_events — 발행 대기 이벤트 (V13)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | 발행 순서 기준 |
| event_id | uuid | NOT NULL, **UNIQUE** | 이벤트 식별자 — Consumer 멱등성 기준 |
| aggregate_type, aggregate_id | varchar(30), bigint | NOT NULL | 이벤트 대상 (지금은 `ORDER`, 주문 id = Kafka 메시지 키) |
| event_type | varchar(50) | NOT NULL, `PAYMENT_COMPLETED` / `PAYMENT_REFUNDED` / `DELIVERY_COMPLETED` | 이벤트 종류 |
| payload | text | NOT NULL | Kafka 메시지 본문(JSON) — 발생 시점 스냅샷 |
| status | varchar(20) | NOT NULL, `PENDING` / `PUBLISHED` / `FAILED` | 발행 대기 / 발행됨 / 재시도 한도 초과 |
| attempt_count | integer | NOT NULL, 0 이상 | 발행 실패 횟수 |
| next_attempt_at | timestamp(6) | NOT NULL | 이 시각 이후 발행 시도 (실패하면 백오프만큼 뒤로) |
| last_error | varchar(500) | | 마지막 실패 원인 요약 |
| created_at | timestamp(6) | NOT NULL | 업무 트랜잭션에서 저장된 시각 |
| published_at | timestamp(6) | `PUBLISHED`일 때만 NOT NULL (CHECK) | 발행 확인 시각 |

> 발행 대상 조회용 부분 인덱스 `idx_outbox_events_pending (next_attempt_at, id) WHERE status = 'PENDING'` — 발행이 끝난 행은 인덱스에 들어가지 않음
> V14 인덱스: `idx_outbox_events_failed (id) WHERE status = 'FAILED'`(FAILED 건수 메트릭·관리자 목록·재발행), `idx_outbox_events_pending_aggregate (aggregate_type, aggregate_id, id) WHERE status = 'PENDING'`(같은 주문의 앞 이벤트가 대기 중인지 확인 — 발행 순서), `idx_outbox_events_published_at (published_at) WHERE status = 'PUBLISHED'`(보관 기간 정리)
> 상태 전이: `PENDING → PUBLISHED` / `PENDING → FAILED`(재시도 한도 초과) / `FAILED → PENDING`(관리자 재발행). `PUBLISHED`는 보관 기간(7일) 뒤 삭제
> 결제·환불·배달완료 트랜잭션에서 업무 데이터와 함께 INSERT, 이후 발행 상태만 UPDATE (업무 금액 기록이 아니라 불변 규칙 대상 아님). 동작은 [프로젝트 구조 · Kafka](architecture.md#kafka--transactional-outbox-42)

### consumed_events — Consumer 처리 기록 (V13)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| event_id | uuid | PK | 처리한 이벤트 — `INSERT ... ON CONFLICT DO NOTHING`으로 중복 처리 방지 |
| event_type | varchar(50) | NOT NULL | 이벤트 종류 |
| consumed_at | timestamp(6) | NOT NULL | 처리 시각 |

> 보관 기간(30일) 뒤 삭제 — `idx_consumed_events_consumed_at (consumed_at)` (V14). 보관 기간은 같은 메시지가 다시 올 수 있는 기간(Kafka 토픽 보관 기간)보다 길어야 함

### notifications — 알림 기록 (V13, 발송 상태 V14)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| event_id | uuid | NOT NULL, (event_id, recipient_user_id) **UNIQUE** | 알림을 만든 이벤트 |
| event_type | varchar(50) | NOT NULL | 이벤트 종류 |
| recipient_user_id | bigint | NOT NULL (FK 없음) | 받는 회원 id (이벤트 스냅샷 값) |
| order_id | bigint | NOT NULL (FK 없음) | 관련 주문 |
| message | varchar(255) | NOT NULL | 알림 문구 |
| created_at | timestamp(6) | NOT NULL | 저장 시각 |
| send_status | varchar(20) | NOT NULL, `PENDING` / `SENT` / `FAILED` / `SKIPPED` | 발송 대기 / 발송됨 / 재시도 한도 초과 / 발송 기능 도입 전 기록(발송 안 함) (V14) |
| send_attempt_count | integer | NOT NULL, 0 이상 | 발송 실패 횟수 (V14) |
| next_send_at | timestamp(6) | NOT NULL | 이 시각 이후 발송 시도 (V14) |
| sent_at | timestamp(6) | `SENT`일 때만 NOT NULL (CHECK) | 발송 시각 (V14) |
| last_send_error | varchar(500) | | 마지막 발송 실패 원인 (V14) |
| read_at | timestamp(6) | NULL 허용 | 처음 읽은 시각, NULL = 안 읽음 (V15, 기존 알림은 NULL) |

> 내용(받는 사람·문구)은 생성 후 바꾸지 않고 발송 상태·읽음 시각만 UPDATE. 인덱스(V14): `idx_notifications_send_pending (next_send_at, id) WHERE send_status = 'PENDING'`(발송 대상), `idx_notifications_recipient (recipient_user_id, id)`(내 알림 목록) / (V15) `idx_notifications_recipient_unread (recipient_user_id) WHERE read_at IS NULL`(안 읽은 개수 — 읽은 알림은 인덱스에 들어가지 않음)
> `read_at`은 `UPDATE ... SET read_at = ? WHERE id = ? AND read_at IS NULL`로만 기록(엔티티에서는 `insertable·updatable = false`) — 엔티티 저장은 모든 컬럼을 UPDATE하므로, 발송기의 상태 변경과 읽음 처리가 서로의 컬럼을 덮어쓰지 않도록 분리. 조건 덕분에 여러 번 읽음 처리해도 처음 시각 유지
> 보관 정리(`maintenance.RetentionCleaner`)는 알림을 지우지 않으므로 읽음 처리와 충돌 없음 (읽은 알림도 계속 남음)
> 세 테이블 모두 업무 테이블에 FK를 두지 않음 — 이벤트는 발생 시점의 독립 기록이라 주문·회원이 지워져도 남아야 하고, FK가 업무 데이터 삭제를 막지 않도록

### payment_idempotency_keys — DB 결제 멱등성 기록 (V14)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| user_id | bigint | NOT NULL, FK → users.id, (user_id, idempotency_key) **UNIQUE** | 결제한 회원 — 키는 사용자별로 구분 |
| idempotency_key | varchar(64) | NOT NULL | `Idempotency-Key` 헤더 값 |
| fingerprint | varchar(64) | NOT NULL | 요청 본문(`orderId`·`paymentMethod`)의 SHA-256 — 같은 키에 다른 요청이면 422 |
| payment_id | bigint | NOT NULL, FK → payments.id, **UNIQUE** | 이 키로 만들어진 결제 |
| created_at | timestamp(6) | NOT NULL | 저장 시각 (결제 시각) |

> 결제와 같은 트랜잭션에 INSERT(키가 있는 결제만) → Redis 기록이 만료·유실되거나 Redis가 내려가 있어도 같은 키 재시도에 처음 응답을 재전송. 응답 본문은 저장하지 않고 결제 행의 불변 값으로 다시 만듦
> 생성 후 변경 없음, 보관 기간(30일) 뒤 삭제 — `idx_payment_idempotency_keys_created_at (created_at)`. 동작은 [프로젝트 구조 · Redis](architecture.md#redis-41)

### refresh_tokens — Refresh Token (V15)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| user_id | bigint | NOT NULL, FK → users.id | 토큰 주인 |
| token_hash | varchar(64) | NOT NULL, **UNIQUE** | 토큰 원문의 SHA-256(hex) — 원문은 저장하지 않음 |
| family_id | uuid | NOT NULL | 로그인 1번 = 계열 1개. 재발급마다 같은 계열에 새 행 |
| created_at | timestamp(6) | NOT NULL | 발급 시각 |
| expires_at | timestamp(6) | NOT NULL, `> created_at`, `<= family_expires_at` | 만료 시각 = min(발급 + 14일, 계열 절대 만료) |
| family_expires_at | timestamp(6) | NOT NULL | 계열 절대 만료 = 로그인 시각 + 30일, 같은 계열은 같은 값 (V16) |
| revoked_at | timestamp(6) | 사유와 함께 NULL / NOT NULL (CHECK) | 폐기 시각 |
| revoke_reason | varchar(20) | `ROTATED` / `LOGOUT` / `REUSE_DETECTED` | 재발급에 쓰임 / 로그아웃 / 같은 계열의 재사용 감지로 함께 폐기 |

> 해시·만료 시각은 생성 후 바뀌지 않고, 폐기 상태만 한 번 바뀜(다시 살리는 경로 없음). `ROTATED` 행이 다시 쓰이면 재사용으로 보고 계열 전체를 `REUSE_DETECTED`로 폐기
> 인덱스: `token_hash` UNIQUE(조회), `idx_refresh_tokens_family_active (family_id) WHERE revoked_at IS NULL`(계열 폐기 대상), `idx_refresh_tokens_expires_at (expires_at)`(보관 정리, V16). 재발급 동시성은 `token_hash`로 `SELECT ... FOR UPDATE` → 같은 토큰의 요청은 한 줄로 처리
> Redis가 아니라 DB에 둔 이유·동작은 [프로젝트 구조 · 인증](architecture.md#인증-access--refresh-token-v15)
> 보관 정리(V16): `RetentionCleaner`가 만료 후 7일 지난 행 삭제 (`retention.refresh-tokens`). 만료 전 행은 폐기됐어도 남김 — 재사용 감지 근거

### owner_applications — 사장님 전환 신청 (V17)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | bigint | PK | |
| user_id | bigint | NOT NULL, FK → users | 신청자 (신청 당시 CUSTOMER) |
| business_name | varchar(50) | NOT NULL | 상호 — 관리자 판단 근거 |
| status | varchar(20) | NOT NULL, CHECK | `REQUESTED` → `APPROVED` / `REJECTED` (한 번만, 엔티티에서 판단) |
| reject_reason | varchar(255) | `REJECTED`일 때만 NOT NULL (CHECK) | 신청자에게 보이는 거절 사유 |
| requested_at | timestamp(6) | NOT NULL | 신청 시각 (`Clock`) |
| decided_at · decided_by | timestamp(6) · bigint FK → users | 처리됐으면 둘 다 NOT NULL, 대기면 둘 다 NULL (CHECK) | 처리 시각·관리자 |
| created_at · updated_at | timestamp(6) | | BaseEntity |

> 인덱스: `uk_owner_applications_user_requested (user_id) WHERE status='REQUESTED'`(대기 중 신청 1건 — 동시 신청의 최종 근거), `idx_owner_applications_status_id`(관리자 목록), `idx_owner_applications_user_id_id`(내 최근 신청)
>
> 승인은 같은 트랜잭션에서 `users.role`을 `OWNER`로 바꿈. 신청 행을 `FOR UPDATE`로 잠가 같은 신청의 승인·거절이 동시에 와도 1건만 성공. users 행은 함께 잠그지 않음 — 승인과 거의 같은 순간에 들어온 새 주문은 막지 못함(진행 중 주문 검사는 승인 직전 1번)

## 결제 연관관계: `@OneToOne` vs `@ManyToOne`

현재는 `@OneToOne` + `order_id` UNIQUE로 **주문 1건당 결제 기록 1건**만 허용해, 중복 결제를 DB 제약으로 막고 있음.
결제 실패 후 재결제, 부분 취소처럼 한 주문에 결제 기록이 여러 건 생기는 요구가 들어오면 `@ManyToOne`으로 바꿔야 함.

| 구분 | `@OneToOne` + UNIQUE (현재) | `@ManyToOne` |
|---|---|---|
| 주문당 결제 기록 | 1건 | N건 |
| 중복 결제 방지 | `order_id` UNIQUE 제약으로 DB가 자동 차단 | 별도 규칙 필요 (예: 성공한 결제는 1건만) |
| 결제 실패·재시도 이력 | 저장 불가 (성공한 결제만 저장) | 실패·재시도 이력을 모두 남길 수 있음 |
| 부분 취소·환불 | 표현하기 어려움 | 취소·환불 건을 별도 행으로 기록 가능 |
| 주문의 결제 조회 | 1건 바로 조회 | 목록을 조회한 뒤 결제 상태로 구분 |

`@ManyToOne`으로 전환할 때 할 일
1. `Payment`에 결제 상태(예: `SUCCESS` / `FAILED` / `CANCELLED`) 추가
2. `@OneToOne` → `@ManyToOne`, `order_id`의 UNIQUE 제거
   - 새 마이그레이션 파일에서 `ALTER TABLE payments DROP CONSTRAINT uk8vo36cen604as7etdfwmyjsxt;`로 삭제
3. 중복 결제 방지 재설계 — 둘 중 하나
   - PostgreSQL 부분 유니크 인덱스: `CREATE UNIQUE INDEX ON payments(order_id) WHERE status = 'SUCCESS'`
   - 결제 시 주문 행에 비관적 락(`@Lock(PESSIMISTIC_WRITE)`)을 걸어 동시 결제를 순서대로 처리
4. `PaymentRepository.existsByOrderId` → 성공한 결제만 확인하도록 변경 (예: `existsByOrderIdAndStatus`)

> 코드에서 바뀌어야 할 위치는 `// TODO(@ManyToOne 전환)` 주석으로 표시해 둠
