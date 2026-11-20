# 목록 API 성능 개선 · 부하 테스트 (#40)

[← README](../README.md)

목록 API 4개(매장·메뉴·주문·매장별 주문)를 페이지 단위로 바꾸고, 주문 목록의 N+1 조회와 인덱스를 개선한 기록.
개선 전후를 같은 데이터·같은 부하(k6)로 측정해 비교함.
주문·결제 쓰기 경로 부하·멱등성 동시 요청·병목 분석(#68)은 [11장](#11-주문결제-쓰기-경로-부하-테스트-68).

## 1. 개선 전 문제

| API | 개선 전 | 문제 |
|---|---|---|
| `GET /api/stores` | `findAll()` | 페이징 없음 — 매장 수만큼 응답이 커짐 |
| `GET /api/menus` | `findAllByDeletedFalse()` | 페이징 없음 — 전체 매장의 메뉴를 한 번에 응답 |
| `GET /api/orders` | `findAllByUserId` / `findAllByStoreOwnerId` | 페이징 없음 + **N+1**: 주문마다 매장 1번(매장 이름), 주문 항목 1번씩 추가 조회. `orders.user_id`에 인덱스 없음 |
| `GET /api/stores/{id}/orders` | `findAllByStoreId` | 페이징 없음 + **N+1**: 주문마다 주문 항목 1번씩 추가 조회 |

- 매장·메뉴 목록은 연관 엔티티의 id만 읽으므로(LAZY 프록시) N+1은 없었음
- 정산 목록(`/api/admin/settlements?date=`, `/api/stores/{id}/daily-settlements`)은 날짜·기간(최대 31일) 조건이 필수라 대상에서 제외

## 2. 변경 내용

| 항목 | 내용 |
|---|---|
| 페이지네이션 | `?page=0&size=20` (기본 20, 최대 100, 범위를 벗어나면 400). `dto.PageQuery` |
| 응답 형식 | 본문은 기존과 같은 JSON 배열. 페이지 정보는 응답 헤더 `X-Page`, `X-Size`, `X-Has-Next` (`controller.SliceResponse`) |
| 정렬 | id 오름차순으로 고정 — 기존 전체 조회가 돌려주던 순서(등록순)와 같고, 고유한 기준이라 페이지끼리 겹치지 않음 |
| 전체 개수 | 제공하지 않음(`Slice`) — 목록을 볼 때마다 조건에 맞는 행을 모두 세는 count 쿼리를 피하고, `size + 1`건 조회로 다음 페이지 여부만 판단 |
| N+1 제거 | 주문 목록: 매장은 같은 쿼리에서 join(`@EntityGraph`), 주문 항목은 IN 쿼리 1번(`@BatchSize`) → 주문 수와 상관없이 **쿼리 3번**(회원 + 주문·매장 + 주문 항목). 1:N인 주문 항목을 fetch join하지 않은 이유: 페이징이 DB가 아닌 메모리에서 처리되기 때문 |
| 인덱스 (V12) | `orders (user_id, id)` 추가, `orders (store_id, id)` 추가 + 역할이 겹치는 `orders (store_id)` 제거 — 근거는 4장 |

- 기존과 달라지는 점: 파라미터 없이 호출하면 전체가 아니라 **첫 20건**만 응답 (나머지는 `page`로 조회)
- 비즈니스 로직·권한·응답 필드는 그대로, 캐시·Redis는 도입하지 않음

## 3. 부하 테스트 구성

```
perf/seed.sql         # 측정용 데이터 (빈 측정 전용 DB에만 실행)
perf/k6/list-api.js   # k6 스크립트 — TARGET으로 API 하나씩 측정
```

| 항목 | 값 |
|---|---|
| 데이터 | 고객 1,000 / 점주 40 / 매장 200 / 메뉴 4,000 / 주문 300,000 / 주문 항목 600,000 |
| 분포 | 고객 1명당 주문 300건, 매장 1곳당 1,500건, 점주 1명당 7,500건(매장 5개) |
| 부하 | 동시 사용자 20명이 대기 없이 반복 요청, API마다 워밍업 10초 후 30초 측정 |
| 도구 | k6 v1.3.0 (호스트에서 직접 실행) |
| 환경 | 노트북 1대(Ryzen 5 7430U 12스레드, RAM 16GB, Windows 11)에 앱(JDK 21, `-Xmx1g`)·PostgreSQL 18(Docker)·k6를 함께 실행 |
| 앱 설정 | local 프로필, SQL 로그 끔(`spring.jpa.show-sql=false`), 스케줄러 끔, 커넥션 풀 기본값(HikariCP 10) |

실행 방법

```bash
# 1. 측정 전용 DB (개발 DB와 분리)
docker run -d --name delivery-perf-db -e POSTGRES_DB=delivery -e POSTGRES_USER=perf -e POSTGRES_PASSWORD=perf \
  -p 127.0.0.1:5433:5432 postgres:18

# 2. 앱 기동 (Flyway가 스키마 생성) — JWT_SECRET은 Base64 256비트 이상 임의 값
./gradlew bootJar
DB_USERNAME=perf DB_PASSWORD=perf JWT_SECRET=... java -Xmx1g -jar build/libs/Spring-Delivery-Mini-0.0.1-SNAPSHOT.jar \
  --server.port=18080 --spring.datasource.url=jdbc:postgresql://localhost:5433/delivery \
  --spring.jpa.show-sql=false --settlement.scheduler.enabled=false

# 3. 데이터 삽입
docker exec -i delivery-perf-db psql -U perf -d delivery -v ON_ERROR_STOP=1 < perf/seed.sql

# 4. 측정 — TARGET: stores | menus | orders-customer | orders-owner | store-orders
# keyset(#54)은 QUERY="?afterId=299106&size=20"처럼 지정 (afterId 산출은 9장)
TARGET=orders-customer BASE_URL=http://localhost:18080 k6 run --quiet perf/k6/list-api.js
TARGET=store-orders QUERY="?page=74&size=20" BASE_URL=http://localhost:18080 k6 run --quiet perf/k6/list-api.js
```

- 개선 전 수치는 이 브랜치를 수정하기 전 커밋(`3e96f42`)으로 빌드한 jar를 같은 DB(V11 스키마)에 붙여 측정
- k6를 Docker 컨테이너로 실행하면 컨테이너 → 호스트 연결에서 `dial: i/o timeout`이 섞여 수치가 왜곡됨 → 호스트에서 직접 실행

## 4. 인덱스 근거 (EXPLAIN)

`EXPLAIN (ANALYZE, BUFFERS)`로 읽은 버퍼 수(8KB 페이지)와 실행 시간을 비교. 캐시에 올라온 상태(shared hit) 기준.

앱은 JDBC 준비된 문장으로 실행하므로 `OFFSET`·`LIMIT` 값을 모르는 계획(generic plan)이 쓰임
→ `SET plan_cache_mode = force_generic_plan` + `PREPARE` / `EXECUTE`로 같은 조건을 만들어 확인.
값을 직접 넣은 `EXPLAIN`만 보면 더 좋은 계획(비트맵 스캔 + 정렬)이 나와 문제가 드러나지 않았고, 부하 테스트에서 매장별 주문의 뒤쪽 페이지가 느린 것(평균 173ms)을 보고 찾음.

### 고객의 주문 목록 — `WHERE user_id = ? ORDER BY id`

| 페이지 | V11 (인덱스 없음) | V12 `(user_id, id)` |
|---|---|---|
| 첫 페이지 | PK 순서 스캔 후 19,980행 버림 — 402 버퍼, 2.6ms | 인덱스로 21행만 — 27 버퍼, 0.2ms |
| 마지막 페이지 (offset 280) | 테이블 전체 병렬 스캔 + 정렬 — 5,249 버퍼, 14.4ms | 해당 고객의 300행만 — 304 버퍼, 1.4ms |

- `orders.user_id`에는 FK만 있고 인덱스가 없었음 (PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않음)
- V11은 주문 전체가 늘어날수록 느려지고, V12는 그 고객의 주문 수에만 비례

### 매장별 주문 목록 — `WHERE store_id = ? ORDER BY id`

| 페이지 | V11 `(store_id)` 등 | V12 `(store_id, id)` |
|---|---|---|
| 첫 페이지 | PK 순서 스캔 후 4,176행 버림 — 87 버퍼, 0.6ms | 인덱스로 21행만 — 24 버퍼, 0.15ms |
| 마지막 페이지 (offset 1,480) | PK 전체 스캔 후 298,500행 버림 — 5,995 버퍼, 38.4ms | 해당 매장의 1,500행만 — 1,508 버퍼, 3.9ms |

- 기존 store_id 인덱스 3개는 id 순서를 주지 못해, 플래너가 "PK를 id 순서로 읽으며 매장을 걸러내는" 계획을 선택 → 뒤쪽 페이지는 다른 매장 주문까지 읽음
- `(store_id)`(V4)는 `(store_id, id)`의 앞부분과 같아 제거 (측정 중 사용 횟수 0) → 주문 쓰기 때 갱신할 인덱스는 고객용 1개만 늘어남

### 추가하지 않은 것

| 대상 | 판단 |
|---|---|
| 점주의 전체 매장 주문 (`stores.owner_id` 조인) | 여러 매장의 주문을 id 순서로 합쳐야 해서 `(store_id, id)`로도 정렬을 피할 수 없음. `store_id IN (...)`으로 바꿔 봐도 같은 계획(PK 전체 스캔) → 인덱스로 해결되지 않아 추가하지 않음 (6장) |
| 매장·메뉴 목록 | PK 순서 스캔으로 필요한 만큼만 읽음 (첫 페이지 2~3 버퍼) → 인덱스 불필요 |
| 주문 항목 일괄 조회 (`order_id IN (...)`) | 기존 `(order_id, menu_id)` UNIQUE 인덱스 사용 (주문 20건 = 80 버퍼, 0.2ms) |

## 5. 측정 결과

### 개선 전후 (같은 요청: 파라미터 없이 호출)

| API | 구분 | 평균 | p95 | 처리량 | 오류율 | 응답 크기 |
|---|---|---|---|---|---|---|
| `GET /api/stores` | 전 | 9.9ms | 14.5ms | 1,937 req/s | 0% | 22.4KB (200건) |
| | 후 | 6.2ms | 9.3ms | 3,089 req/s | 0% | 2.2KB (20건) |
| `GET /api/menus` | 전 | 120.7ms | 158.1ms | 160 req/s | 0% | 558KB (3,800건) |
| | 후 | 6.9ms | 10.1ms | 2,796 req/s | 0% | 2.8KB |
| `GET /api/orders` (고객) | 전 | 1,205ms | 1,443ms | 16 req/s | 0% | 118KB (300건) |
| | 후 | 18.0ms | 28.5ms | 1,082 req/s | 0% | 7.7KB |
| `GET /api/orders` (점주) | 전 | 15,982ms | 18,155ms | 1.1 req/s | 0% | 2,884KB (7,500건) |
| | 후 | 19.0ms | 25.4ms | 1,028 req/s | 0% | 7.6KB |
| `GET /api/stores/{id}/orders` | 전 | 3,370ms | 3,717ms | 5.7 req/s | 0% | 574KB (1,500건) |
| | 후 | 22.2ms | 35.3ms | 881 req/s | 0% | 7.6KB |

- 주문 목록 3개: 평균 응답 시간 1/67 ~ 1/840, 처리량 66 ~ 930배
  - 요청당 쿼리 수: 고객 약 500번(회원 1 + 주문 1 + 매장 200 + 항목 300) → 3번, 점주 7,507번 → 3번, 매장별 1,503번 → 4번
- 메뉴 목록: 평균 1/17 — 쿼리는 원래 1번이었고, 3,800건 조회·직렬화가 20건으로 줄어든 효과
- 매장 목록: 데이터가 200건뿐이라 차이가 작음 (매장이 늘어도 응답 시간이 유지된다는 점이 개선)
- 이 비교는 "전체 조회 → 첫 페이지 20건"이므로 페이지네이션 효과가 대부분. 인덱스 효과는 아래 뒤쪽 페이지에서 구분됨

### 개선 후 — 뒤쪽 페이지·최대 크기

| 요청 | 평균 | p95 | 처리량 | 오류율 |
|---|---|---|---|---|
| 고객 주문 마지막 페이지 (`?page=14&size=20`) | 19.8ms | 27.6ms | 985 req/s | 0% |
| 매장별 주문 마지막 페이지 (`?page=74&size=20`) | 22.4ms | 29.8ms | 872 req/s | 0% |
| 점주 주문 마지막 페이지 (`?page=374&size=20`) | **538ms** | **902ms** | 37 req/s | 0% |
| 고객 주문 최대 크기 (`?size=100`, 39KB) | 28.2ms | 41.0ms | 687 req/s | 0% |

- 매장별 주문 마지막 페이지: `(store_id, id)` 인덱스가 없을 때 평균 173ms · 110 req/s → 22.4ms · 872 req/s (인덱스만의 효과. 인덱스 없는 쪽은 k6를 Docker로 실행했을 때의 값이라 참고용)
- 고객·매장별 주문은 첫 페이지와 마지막 페이지의 응답 시간이 거의 같음

## 6. 남은 병목

| 항목 | 내용 | 방향 |
|---|---|---|
| 점주 전체 주문의 뒤쪽 페이지 | 마지막 페이지 평균 538ms. 쿼리 1번이 68ms(7,969 버퍼, 병렬 워커 2개)인데 주문 전체를 id 순서로 읽으며 본인 매장만 걸러냄 → 주문 테이블 크기에 비례, 동시 요청이 몰리면 CPU 경합 | **#54에서 해결**: `?afterId=` keyset 페이징 추가 → 마지막 페이지 17.6ms (9장) |
| OFFSET 페이징 공통 | 뒤쪽 페이지일수록 건너뛸 행을 읽고 버림 (매장별 마지막 페이지 1,508 버퍼 vs 첫 페이지 24). 지금 규모에서는 응답 시간 차이가 거의 없음 | 고객·매장별 주문·매장·메뉴 목록은 차이가 작아 OFFSET 유지. keyset은 병목이 확인된 주문 목록에만 적용(#54, 고객도 같은 API라 함께 지원) |
| 요청마다 회원 조회 | 인증이 필요한 목록은 `findByUsername` 1번이 항상 추가됨 (DB 기준 역할 재확인 규칙) | `username` UNIQUE 인덱스로 1행만 읽는 조회라 유지. 캐시는 도입하지 않음 |
| 정산 목록 | 페이징 없음. 관리자 날짜별 목록은 매장 수만큼, 사장님 목록은 최대 31건 | 매장 수가 크게 늘면 같은 방식(`PageQuery`)으로 적용 |

## 7. 테스트 환경과 운영 환경의 차이

이 수치는 **개선 전후의 상대 비교**용이며 운영 성능 예측치가 아님.

| 구분 | 테스트 환경 | 운영 환경에서 달라지는 점 |
|---|---|---|
| 배치 | 노트북 1대에 앱·DB·k6가 함께 실행 (CPU·메모리 경합, 네트워크 지연 0) | 앱·DB가 분리되면 쿼리마다 네트워크 왕복이 추가됨 → 쿼리 수가 많던 개선 전 방식은 여기 수치보다 더 느려짐 |
| 데이터 | 균등하게 만든 합성 데이터 30만 건, 전부 메모리(shared buffers)에 올라온 상태 | 특정 매장·고객에 주문이 몰리고, 메모리보다 큰 데이터는 디스크 읽기가 발생 → 읽는 버퍼 수가 적은 계획의 이점이 더 커짐 |
| 부하 | 로그인 계정 1개로 같은 요청을 반복 (같은 행을 계속 조회), 쓰기 없음 | 여러 사용자가 서로 다른 데이터를 조회하고 주문·결제 쓰기가 섞임 → 캐시 적중률 하락, 락·인덱스 갱신 비용 발생 |
| 설정 | JVM `-Xmx1g`, HikariCP 10개, PostgreSQL 기본 설정, HTTP(평문) | 인스턴스 크기·풀 크기·DB 파라미터·TLS에 따라 절대 수치가 달라짐 |
| 측정 | API별 30초, 1회 측정 | 실행마다 편차가 있어 작은 차이(매장 목록 등)는 의미를 두기 어려움. 장시간 부하·GC·커넥션 고갈은 확인하지 않음 |
| 마이그레이션 | 빈 DB에 인덱스 생성 | 주문이 쌓인 운영 DB에서는 `CREATE INDEX`가 쓰기를 막음 → `CREATE INDEX CONCURRENTLY` 검토 (V12 주석) |

## 8. 테스트 결과

`./gradlew build` (2026-11-14, Testcontainers PostgreSQL 18) — 부하 테스트 수치는 5장

| 구분 | 결과 |
|---|---|
| 전체 | 52개 클래스 · **463건 통과** (실패 0, 오류 0, 건너뜀 0) |

이번 변경을 검증하는 테스트

| 테스트 | 건수 | 확인한 내용 |
|---|---|---|
| `OrderListIndexMigrationTest` (신규) | 4 | V11 데이터가 있는 DB에 V12 적용: `(user_id, id)`·`(store_id, id)` 인덱스 생성, 겹치는 `idx_orders_store_id` 제거·나머지 매장 인덱스 유지, 기존 주문 유지, 고객·매장별 페이지 조회가 새 인덱스로 정렬 없이 처리 |
| `StoreOrderIntegrationTest` | 10 (3건 추가) | 주문 목록이 id 오름차순으로 페이지끼리 겹치지 않고 `hasNext`가 맞음, 범위를 넘은 페이지는 빈 배열, **주문 8건 조회가 고객·점주 모두 쿼리 3번**(N+1 없음), 매장·메뉴 목록 페이지 조회와 삭제 메뉴 제외 |
| `StoreSecurityTest` | 18 (3건 추가) | 파라미터 없으면 첫 페이지 20건을 배열로 응답 + `X-Page`·`X-Size`·`X-Has-Next` 헤더, `page`·`size`가 서비스로 전달, 범위 초과·형식 오류(`size=101`, `size=0`, `page=-1`, `page=abc`) 400 |
| `OrderServiceTest` | 33 (1건 추가) | 요청한 페이지를 id 오름차순 `Pageable`로 조회, 기존 권한 검증(다른 점주 403, CUSTOMER 403)은 그대로 |
| `SettlementIntegrationTest` | 4 | 바뀐 목록 메서드 시그니처로 기존 검증 유지 |

- 쿼리 수는 Hibernate `Statistics.getPrepareStatementCount()`로 실제 PostgreSQL에서 측정
- 나머지 테스트(결제·정산·환불·수수료 등)는 수정 없이 통과 → 기존 비즈니스 로직에 영향 없음

## 9. 점주 전체 주문 keyset 페이징 (#54)

6장의 남은 병목(점주 전체 주문 마지막 페이지 538ms)을 해결하기 위해 주문 목록에 keyset 페이징을 추가함.

### 변경 내용

| 항목 | 내용 |
|---|---|
| 요청 | `GET /api/orders?afterId={마지막으로 받은 주문 id}&size=20` (`dto.KeysetQuery`, `afterId` 0 이상·`size` 1~100, `page`와 함께 보내면 400) |
| 쿼리 | `WHERE s.owner_id = ? AND o.id > ? ORDER BY o.id LIMIT size + 1` (`OrderRepository.findAllByStoreOwnerIdAndIdGreaterThan`), 고객은 `(user_id, id)` 인덱스 범위만 읽음 |
| 응답 | 본문은 기존과 같은 배열. 헤더 `X-Size`·`X-Has-Next`·`X-Next-After-Id`(다음 페이지가 있을 때만) — `X-Page`는 보내지 않음 |
| 호환성 | `?page=` 방식은 그대로 유지, 응답에 `X-Next-After-Id`만 추가 → 기존 클라이언트는 변경 없이 동작하고 원하면 keyset으로 이어갈 수 있음 |
| 조회 조건·정렬 | 대상(고객 본인 주문 / 점주 본인 모든 매장 주문)·권한·id 오름차순 정렬은 기존과 같음, 쿼리 수 3번(N+1 없음)도 같음 |
| 인덱스 | 추가하지 않음 — 주문 PK를 `afterId` 다음부터 읽고 첫 21건에서 멈추므로 기존 인덱스로 충분 (아래 EXPLAIN) |

중복·누락이 없는 이유: 정렬 기준 id가 고유하고 매번 "마지막으로 받은 id보다 큰 것"만 조회하므로 페이지 경계에 같은 행이 두 번 오거나 빠지지 않음. OFFSET과 달리 넘기는 중 새 주문이 들어와도(새 id는 항상 더 큼) 앞 페이지 행이 밀려 다시 나오지 않고 마지막에 이어짐.

### DB 측정 (EXPLAIN ANALYZE, 같은 데이터)

점주(`perf_owner`, 주문 7,500건) — 중간은 3,741번째, 마지막은 7,481번째 주문부터 21건 조회. 각 6회 실행 범위

| 요청 | 계획 | 읽은 버퍼 | 실행 시간 |
|---|---|---|---|
| OFFSET 첫 페이지 (`OFFSET 0`) | 주문 PK 순서로 읽으며 매장 join | 419 | 0.6ms |
| OFFSET 중간 (`OFFSET 3740`) | 점주 주문 전체를 읽고 정렬(Gather Merge + Sort) | 5,198 | 26.3 ~ 30.8ms |
| OFFSET 마지막 (`OFFSET 7480`) | 같음 | 5,198 | 27.2 ~ 29.4ms |
| keyset 중간 (`id > 149456`) | 주문 PK를 afterId 다음부터 읽다가 21건에서 멈춤 | 427 | 0.7ms |
| keyset 마지막 (`id > 299106`) | 남은 주문만 읽음 | 30 | 0.4ms |

- OFFSET은 뒤쪽 페이지일수록 앞의 행을 모두 읽고 버리므로 점주 주문 수에 비례, keyset은 위치와 무관하게 첫 페이지 수준
- 6장 수치(68ms, 7,969 버퍼)와 차이는 실행 계획 선택 차이(이번 측정은 새로 만든 DB + `ANALYZE` 직후). 결론(뒤쪽 페이지 = 전체 스캔)은 같음

afterId 산출 (OFFSET 페이지와 같은 위치 비교용)

```sql
SELECT id FROM (SELECT o.id, row_number() OVER (ORDER BY o.id) rn
                FROM orders o JOIN stores s ON s.id = o.store_id WHERE s.owner_id = 1001) t
WHERE rn IN (3740, 7480);   -- 149456, 299106
```

### 부하 측정 (k6, 동시 사용자 20명 · 워밍업 10초 후 30초)

| 위치 | 방식 | 평균 | p95 | 처리량 | 오류율 |
|---|---|---|---|---|---|
| 첫 페이지 | OFFSET `?page=0` | 18.9ms | 30.1ms | 1,034 req/s | 0% |
| | keyset `?afterId=0` | 18.0ms | 26.7ms | 1,087 req/s | 0% |
| 중간 (3,741번째~) | OFFSET `?page=187` | 215.7ms | 273.1ms | 92 req/s | 0% |
| | keyset `?afterId=149456` | **19.3ms** | **29.2ms** | **1,015 req/s** | 0% |
| 마지막 (7,481번째~) | OFFSET `?page=374` | 426.2ms | 538.7ms | 47 req/s | 0% |
| | keyset `?afterId=299106` | **17.6ms** | **24.3ms** | **1,111 req/s** | 0% |

- 마지막 페이지: 평균 1/24, 처리량 24배. keyset은 첫·중간·마지막 페이지 응답 시간이 거의 같음
- 첫 페이지는 두 방식이 같은 계획이라 차이 없음 (오차 범위)
- 환경은 3장과 같음(같은 노트북·seed·k6 v1.3.0, 커밋 전 작업 트리로 빌드한 jar). 이번 OFFSET 마지막 페이지(426ms)와 5장(538ms)의 차이는 실행마다의 편차·계획 차이 — 비교는 같은 실행 안의 OFFSET·keyset끼리만 의미 있음 (7장)

### 한계

- 임의 페이지로 바로 이동(예: 50페이지)은 keyset으로 할 수 없음 → 필요하면 기존 `?page=` 사용
- 정렬 기준이 id 하나라 가능한 방식. 다른 정렬(예: 상태별·금액순)을 추가하면 `(정렬 컬럼, id)` 복합 조건과 인덱스가 필요
- 특정 점주의 주문이 전체 중 아주 적으면(예: 0.1%) 주문 PK를 읽으며 걸러내는 행이 늘어남 → 그때는 `(store_id, id)` 인덱스를 매장별로 읽어 합치는 방식 검토. 지금 분포(점주당 2.5%)에서는 페이지당 약 400 버퍼

### 테스트

`./gradlew test` (2026-11-15) — 전체 73개 클래스 · **637건 통과** (실패 0, 오류 0, 건너뜀 0)

| 테스트 | 건수 | 확인한 내용 |
|---|---|---|
| `StoreOrderIntegrationTest` | 14 (4건 추가) | 점주 keyset으로 끝까지 넘기면 OFFSET과 같은 순서·내용으로 중복·누락 없음(다른 점주 주문이 사이에 섞인 상태), 경계(`afterId=0`=첫 페이지, 마지막 id 다음은 빈 결과, 다른 점주 주문 id를 afterId로 줘도 그 다음 본인 주문부터, 정확히 size건 남으면 `hasNext=false`), 넘기는 중 새 주문이 들어와도 이미 받은 주문이 다시 나오지 않고 뒤에 이어짐, 고객 keyset은 본인 주문만·고객·점주 모두 쿼리 3번 |
| `OrderListPagingTest` (신규) | 8 | 파라미터 없으면 기존처럼 배열 + `X-Page`·`X-Size`·`X-Has-Next` + `X-Next-After-Id`, keyset 응답은 `X-Page` 없음, 마지막·빈 페이지는 `X-Next-After-Id` 없음, `page`+`afterId` 400, `afterId=-1`·`abc`·`size=0`·`101` 400, 토큰 없으면 401 |
| `OrderServiceTest` | 35 (2건 추가) | keyset은 OFFSET 0 + id 오름차순 `Pageable`로 조회, 고객은 본인 주문 쿼리·ADMIN은 빈 목록 |

## 10. 내 결제 목록 (#62)

`GET /api/payments?page=&size=` — 고객 본인 결제를 id 오름차순으로 (`PaymentRepository.findAllByOrderUserId`, `@EntityGraph("order")`)

```sql
SELECT p.*, o.* FROM payments p JOIN orders o ON o.id = p.order_id
WHERE o.user_id = ? ORDER BY p.id LIMIT ? OFFSET ?
```

### 인덱스 판단 (추가하지 않음, V15 미사용)

| 단계 | 사용하는 인덱스 |
|---|---|
| 본인 주문 찾기 (`orders.user_id = ?`) | `idx_orders_user_id_id` (V12) |
| 주문의 결제 (`payments.order_id`) | `payments.order_id` UNIQUE 제약의 인덱스 (V1) |
| `ORDER BY p.id` | 인덱스로 해결 불가 → 그 고객의 결제를 모두 읽고 정렬 |

- `payments`에는 `user_id`가 없어 `(user_id, id)` 같은 인덱스로 정렬까지 없앨 수 없음. 없애려면 결제에 주문자 컬럼을 중복 저장(반정규화)해야 하는데, 결제 행은 불변이라 값은 안전하지만 스키마·쓰기 경로 변경에 비해 이득이 작음
- 읽는 행 수 = 그 고객의 결제 수 (결제 ≤ 주문, 주문당 결제 1건). 고객 한 명의 결제는 많아야 수백~수천 건이라 페이지 위치와 관계없이 작음 — 주문 목록 V11의 "전체 주문에 비례"하는 문제와 다름
- 주문 목록(4장)처럼 고객 결제가 매우 많아져 정렬 비용이 보이면 그때 `payments.user_id` + `(user_id, id)` 인덱스 또는 주문 id 기준 정렬을 검토
- EXPLAIN 실측은 하지 않음 (로컬 데이터가 작아 계획이 의미 없음). 근거는 위 인덱스 구성

### 주문 단건 조회 (`GET /api/orders/{id}`)

- 주문·매장·항목을 `JOIN FETCH` 한 쿼리로 (단건이라 컬렉션 fetch join의 메모리 페이징 문제 없음) + 결제는 `order_id` UNIQUE 인덱스로 1건 → 회원 포함 쿼리 3번, 항목 수와 무관 (`OrderQueryIntegrationTest`에서 Hibernate Statistics로 확인)

## 11. 주문·결제 쓰기 경로 부하 테스트 (#68)

고객 로그인 → 주문 생성 → 결제(`Idempotency-Key`)를 동시 사용자 10·20·50명으로 측정하고, 같은 키 동시 결제에서 중복 결제가 생기지 않는지 확인.
**노트북 1대 측정이라 절대 수치보다 단계별 추세·병목 위치가 목적** (7장 환경 차이 그대로 적용). 이번 이슈에서 코드는 바꾸지 않음 — 개선 후보는 마지막 절에 정리하고 별도 이슈로 분리.

### 구성

```
perf/k6/order-payment.js   # SCENARIO=load(단계별 측정) | idempotency(같은 키 동시 결제)
perf/compose.perf.yml      # compose 앱만 측정 전용 DB(delivery_perf)에 붙이는 덮어쓰기 파일
```

| 항목 | 값 |
|---|---|
| 데이터 | `perf/seed.sql`(주문 30만 건, 3장)을 넣은 `delivery_perf` + 측정마다 새 고객 계정(실행마다 `RUN_ID` 접두사) |
| 흐름 (load) | VU마다 계정 1개(setup에서 가입·로그인) → 반복: 주문 생성(같은 매장 메뉴 2개) → 결제(반복마다 새 키). 대기 없이 반복 |
| 확인 (check) | 주문 201, 결제 201, 재응답 헤더 없음, 결제 금액 = 주문 총액 → 하나라도 어긋나면 오류율에 포함 |
| 측정 구간 | 워밍업 10초(통계 제외) 후 60초, 단계마다 1회. 단계 사이에는 Outbox 대기 0건까지 기다림 |
| 도구 | k6 v1.3.0 Docker 이미지(`grafana/k6:1.3.0`)를 **compose 네트워크 안에서** 실행 → `http://app:8080` 직접 호출 |
| 환경 | 노트북(Ryzen 5 7430U 12스레드, RAM 16GB, Windows 11), Docker Desktop(CPU 12, 메모리 7.4GiB)에 앱·PostgreSQL 18·Redis·Kafka·Prometheus·Grafana·k6가 함께 실행 |
| 앱 설정 | dev 프로필 compose 앱(JVM 힙 기본값), HikariCP 10, 알림 발송기 = 로그 발송기(기본값, Slack 지연 배제), Outbox 발행 batch 100·주기 1초(기본값) |
| 관찰 | Prometheus(15초 수집) 측정 구간 값 + `pg_stat_activity` 1초 샘플(대기 이벤트) + `docker stats` 3초 샘플 |

- #40은 k6 컨테이너 → **호스트 포트** 연결에서 `dial: i/o timeout`이 섞여 호스트 실행을 택했음(3장). 이번에는 같은 Docker 네트워크의 컨테이너끼리 연결이라 그 경로를 거치지 않으며, 모든 실행에서 오류 0건
- 개발 DB(`delivery`)와 분리한 이유: 측정 중 결제 수만 건이 검증 데이터·정산 집계에 섞이지 않도록. Redis·Kafka는 공유하되 계정이 실행마다 새로 만들어져 멱등성 키가 섞이지 않음

실행 방법 (Git Bash 기준)

```bash
# 0. 모니터링 포함 compose 실행 중이라고 가정 (APP_PORT=8090 docker compose --profile monitoring up -d)
# 1. 측정 전용 DB를 만들고 앱을 그 DB로 재기동 (Flyway가 스키마 생성) → 데이터 삽입
docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d delivery -c "CREATE DATABASE delivery_perf"'
APP_PORT=8090 docker compose -f docker-compose.yml -f perf/compose.perf.yml --profile monitoring up -d app
docker compose exec -T db sh -c 'psql -q -U "$POSTGRES_USER" -d delivery_perf -v ON_ERROR_STOP=1' < perf/seed.sql

# 2. 단계별 측정 — 재기동 직후라면 한 번 버리는 실행으로 JIT 예열
export MSYS_NO_PATHCONV=1   # Git Bash가 /scripts 경로를 바꾸지 않도록
K6="docker run --rm --network spring-delivery-mini_default -v $(pwd -W)/perf/k6:/scripts:ro"
$K6 -e VUS=10 grafana/k6:1.3.0 run --quiet /scripts/order-payment.js      # 20, 50도 같은 방식
$K6 -e VUS=20 -e IDEMPOTENCY=off grafana/k6:1.3.0 run --quiet /scripts/order-payment.js   # 키 없는 결제 비교

# 3. 멱등성: 주문 ROUNDS건 × 같은 키 동시 요청 CONCURRENCY건
$K6 -e SCENARIO=idempotency -e CONCURRENCY=50 -e ROUNDS=20 grafana/k6:1.3.0 run --quiet /scripts/order-payment.js

# 4. DB 기준 중복 결제 재확인 (0이어야 함)
docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d delivery_perf -c "SELECT count(*) FROM (SELECT order_id FROM payments GROUP BY order_id HAVING count(*) > 1) d"'

# 5. 되돌리기: 앱을 원래 DB로 재기동 (delivery_perf는 남음, 필요 없으면 DROP DATABASE delivery_perf)
APP_PORT=8090 docker compose --profile monitoring up -d app
```

측정 중 DB 대기 이벤트 샘플 (psql에서 실행, 1초 간격)

```sql
SELECT coalesce(wait_event_type, 'CPU') AS type, coalesce(wait_event, '-') AS event, state, count(*)
FROM pg_stat_activity
WHERE datname = 'delivery_perf' AND backend_type = 'client backend' AND state <> 'idle'
GROUP BY 1, 2, 3 \watch 1
```

- 출력은 `RESULT ...` 한 줄(처리량·오류율·p50/p95/max). 처리량 `flows_per_s` = 측정 구간에 끝난 "주문 + 결제" 흐름 수 / 60초
- Grafana(`Delivery App` 대시보드)의 "DB 커넥션 풀", "Outbox 이벤트", "가장 오래된 발행 대기 이벤트 경과 시간" 패널로 측정 중 추이를 봄. 아래 표의 값은 같은 지표를 Prometheus API로 측정 구간(75초 = 측정 60초 + 수집 간격 15초)에 대해 조회한 것

### 측정 결과 (2026-11-20, 단계별 1회)

클라이언트(k6) 기준

| 동시 사용자 | 처리량 (흐름/s) | 오류율 | 주문 p50 / p95 (ms) | 결제 p50 / p95 (ms) | 흐름 p95 (ms) | 흐름 max (ms) |
|---|---|---|---|---|---|---|
| 10 | 184.0 | 0% | 14.3 / 54.4 | 23.1 / 81.6 | 128.1 | 629.7 |
| 20 | 284.7 | 0% | 23.2 / 66.9 | 32.5 / 85.4 | 138.1 | 829.8 |
| 50 | 303.7 | 0% | 63.7 / 190.0 | 73.9 / 205.6 | 349.0 | 1,127.8 |

서버·DB 지표 (같은 실행)

| 동시 사용자 | 서버 평균 주문 / 결제 (ms) | Hikari active 최대 | Hikari pending 최대 | 커넥션 획득 대기 평균 / 최대 (ms) | 커넥션 보유 평균 (ms) | 획득 시간 초과 | Outbox 대기 최대 (건) | 가장 오래된 대기 (s) | 호스트 CPU 최대 |
|---|---|---|---|---|---|---|---|---|---|
| 10 | 16.0 / 26.6 | 9 | 0 | 0.3 / 62 | 18.0 | 0 | 9,692 | 53 | 89% |
| 20 | 24.0 / 34.1 | 10 | 11 | 9.5 / 400 | 13.3 | 0 | 13,688 | 54 | 83% |
| 50 | 69.7 / 80.9 | 10 | 39 | 55.6 / 720 | 14.7 | 0 | 14,788 | 51 | 84% |

- 커넥션 보유 시간 = `hikaricp_connections_usage_seconds`(빌린 뒤 반납까지), 획득 대기 = `hikaricp_connections_acquire_seconds` — 둘 다 누적 카운터로 낸 평균이라 15초 수집 간격과 무관. active·pending·Outbox 대기는 게이지라 15초 간격 샘플의 최댓값(사이의 순간 최댓값은 놓칠 수 있음)
- 호스트 CPU = `system_cpu_usage`(Docker Desktop VM 전체). 같은 구간 `docker stats` 최대: 앱 3.6~7.4코어, DB 2.6~2.8코어, Kafka 1.2~2.1코어
- DB 대기 이벤트(1초 샘플 95회): `Lock`(행 잠금 대기) **0회**(전 단계). 가장 많은 것은 `ClientRead`·`idle in transaction`(트랜잭션 안에서 앱의 다음 문장을 기다림) > CPU > `WALWrite`·`WalSync`(커밋 시 WAL 기록)

비교 실행 (병목 위치 확인용, 1회씩)

| 실행 | 처리량 (흐름/s) | 결제 p50 / p95 (ms) | 흐름 p95 (ms) | 서버 평균 결제 (ms) | 획득 대기 평균 (ms) | 커넥션 보유 평균 (ms) |
|---|---|---|---|---|---|---|
| 20명, 키 있음 (위 표) | 284.7 | 32.5 / 85.4 | 138.1 | 34.1 | 9.5 | 13.3 |
| 20명, **키 없음** (`IDEMPOTENCY=off`) | 340.3 | 22.3 / 69.9 | 124.4 | 23.8 | 8.4 | 11.4 |
| 50명, 풀 10 (위 표) | 303.7 | 73.9 / 205.6 | 349.0 | 80.9 | 55.6 | 14.7 |
| 50명, **풀 20** (`DB_POOL_SIZE=20`, 예열 후) | 314.3 | 72.8 / 212.7 | 347.5 | 75.2 | 29.1 | 29.3 |

- 풀 20은 앱 재기동 직후 1회가 182.8 흐름/s로 크게 낮았음(JIT 예열 전) → 버리고 예열 뒤 다시 측정한 값. 재기동 후에는 버리는 실행이 필요
- 풀 20 실행은 compose 덮어쓰기 파일을 하나 더 붙여 `DB_POOL_SIZE: "20"`을 넘김 (측정 뒤 원래 설정으로 재기동)

### 멱등성 — 같은 키 동시 결제

주문 20건 각각에 대해 VU N명이 같은 시각(라운드 시작 시각까지 대기 후)에 **같은 `Idempotency-Key`·같은 본문**으로 결제 요청.

| 동시 요청 | 라운드(주문) | 요청 | 새 결제 (201) | 재응답 (201 + `Idempotent-Replayed`) | 409 (처리 중) | 그 외 응답 | 응답 결제 ≠ 실제 결제 | 결제 2건 이상인 주문 |
|---|---|---|---|---|---|---|---|---|
| 20 | 20 | 400 | **20** | 62 | 318 | 0 | 0 | **0** |
| 50 | 20 | 1,000 | **20** | 56 | 924 | 0 | 0 | **0** |

- 판정: 라운드마다 새 결제 정확히 1건. 재응답의 결제 id는 `GET /api/orders/{id}`의 `payment.id`와 모두 같음(다른 결제를 돌려주지 않음). 끝난 뒤 그 고객의 결제 목록도 20건
- DB 재확인(실행 방법 4): 측정 전체 결제 53,538건 = 서로 다른 주문 53,538건, 주문당 2건 이상 **0**
- 대부분이 409인 이유: 첫 요청이 Redis 처리 중 기록(`SET NX`)을 잡은 뒤 결제가 끝나기 전에 도착한 요청 → 409(처리 중), 끝난 뒤 도착한 요청 → 처음 응답 재전송. 409가 대부분이라는 것은 요청들이 실제로 처리 시간 안에 겹쳤다는 뜻
- 이 시나리오가 확인한 것은 Redis 단계(`SET NX`)에서 걸러지는 경로. Redis 기록이 없을 때 DB 제약(`payments.order_id` UNIQUE, 멱등성 기록 UNIQUE)이 막는 경로는 `PaymentIdempotencyIntegrationTest`·`PaymentIdempotencyRedisFailureTest`가 확인

### 병목 분석

**1. 처리량 상한 약 300 흐름/s — 위치: 노트북 CPU (커넥션 풀 아님)**

- 20 → 50명에서 처리량 +7%(284.7 → 303.7), 흐름 p95 2.5배(138 → 349ms) → 늘어난 사용자는 처리되지 않고 대기 시간으로만 쌓임
- 처음 보이는 대기는 커넥션 풀: 50명에서 pending 최대 39, 획득 대기 평균 55.6ms = 서버 평균 응답(70~80ms)의 대부분. 반면 커넥션 보유 시간은 13~18ms로 단계와 무관하게 일정 → DB 쪽 처리가 느려진 것이 아니라 풀 앞에서 줄을 섬
- 그러나 **풀을 20으로 늘려도 처리량이 같음**(303.7 → 314.3, 흐름 p95 349 → 347.5ms): 획득 대기는 절반(55.6 → 29.1ms)이 됐지만 보유 시간이 2배(14.7 → 29.3ms) → 대기가 풀 앞에서 커넥션 안(DB·앱 CPU)으로 옮겨갔을 뿐
- 호스트 CPU가 10명부터 83~89%이고, DB 대기 1위가 `ClientRead`·`idle in transaction`(DB가 트랜잭션 중 앱의 다음 문장을 기다림 = 앱이 CPU를 제때 못 받음) → 앱·DB·Kafka·k6가 한 CPU를 나눠 쓰는 것이 상한
- 행 잠금 대기 0회: 주문마다 다른 행을 `FOR UPDATE`로 잠그므로 경합 없음. `WALWrite`·`WalSync`가 보이지만 CPU·`ClientRead`보다 적음 → 커밋 I/O는 2순위
- 결론: **이 환경에서는 풀 크기 조정이 효과 없음** → 기본값(10) 유지. 앱·DB가 분리된 환경에서 다시 측정해야 풀·DB 중 어느 쪽이 다음 상한인지 알 수 있음

**2. Outbox 발행 처리량 상한 약 92건/s — 이번 측정에서 가장 뚜렷한 구조적 병목**

- 결제 1건 = Outbox 이벤트 1건(`PAYMENT_COMPLETED`). 발행기는 한 주기에 batch 100건을 보내고 `fixed-delay` 1초를 쉼 → 발행 속도 상한 ≈ 100건 / (1초 + batch 처리 시간). 측정 뒤 대기 건이 줄어드는 속도 실측: **1,000건 / 약 10.8초 ≈ 92건/s**
- 결제는 184~314건/s → 모든 단계에서 대기 건수가 계속 증가 (최대 9,692 → 20,857건, 가장 오래된 대기 51~82초). 측정이 끝난 뒤 대기 0건까지 2~4분
- 영향: 결제 응답은 영향 없음(커밋과 발행이 분리된 설계대로). 결제 완료 **알림이 늦어짐**
- 대기는 Kafka가 아니라 `outbox_events`에 쌓임: 알림 Consumer lag은 측정 전체에서 최대 695건(`NotificationConsumerLag` 1000건 미만) → Consumer는 발행 속도를 따라감
- 지속 부하에서의 예상(계산, 실측 아님): 결제 300건/s가 계속되면 가장 오래된 대기 ≈ 경과 시간 × (1 − 92/300) ≈ 0.7배로 증가 → 약 7분이면 `OutboxPublishDelayed`(300초, `monitoring/alerts.yml`) 발생
- Kafka·발행 쿼리가 원인은 아님: 15,000건 대기를 재현(트랜잭션 안에서 상태 변경 후 롤백)한 `findPublishableForUpdate` EXPLAIN ANALYZE = **23ms**(주기 1초 대비 작음). 다만 계획이 부분 인덱스(`idx_outbox_events_pending`) 대신 PK 순서로 읽으며 `PUBLISHED` 62,752행을 걸러냄 → `PUBLISHED`가 보관 기간(7일) 동안 쌓이면 늘어날 수 있음 (통계 갱신 전 트랜잭션 안 측정이라 참고치)
  - EXPLAIN 시 `:now`는 `now() AT TIME ZONE 'Asia/Seoul'` — 앱이 KST 로컬 시각(`LocalDateTime.now(clock)`)을 저장하므로 DB `now()`(UTC)를 그대로 쓰면 0건으로 나옴

**3. 멱등성 처리 비용 — 결제당 약 10ms (이 환경)**

- 키 있음/없음(20명): 서버 평균 결제 34.1 → 23.8ms, 처리량 284.7 → 340.3 흐름/s
- 키가 있으면 추가되는 일: Redis 왕복 2번(`SET NX` 처리 중 기록, 완료 기록 Lua) + DB 트랜잭션 1개(`findIdempotentPayment` 선조회, 읽기 전용) + 멱등성 기록 INSERT 1번
- 이 10ms가 허용 범위인지는 목표 응답 시간이 없어 판단하지 않음. 줄인다면 선조회를 결제 트랜잭션에 합치는 방법이 있으나 Redis 기록 유실 시 동작(`docs/architecture.md` Redis 장)을 다시 검증해야 함

### 개선 후보 (별도 이슈로 분리, 이번 범위에서 변경하지 않음)

| 후보 | 근거 | 비고 |
|---|---|---|
| Outbox 발행 처리량 — batch가 가득 차면 쉬지 않고 다음 batch 발행(drain), 또는 `batch-size`·`fixed-delay` 조정 | 발행 92건/s < 결제 184~314건/s, 대기 시간 선형 증가 | 운영 결제량 추정이 먼저. 순서 보장(NOT EXISTS)·SKIP LOCKED 동작은 유지 |
| 발행 쿼리 계획 확인 (`PUBLISHED`가 많을 때 PK 순서 스캔) | EXPLAIN에서 PUBLISHED 62,752행 필터링(23ms) | 보관 7일치 데이터로 `ANALYZE` 후 재측정 |
| 앱·DB 분리 환경에서 쓰기 경로 재측정 | 이 환경의 상한은 CPU 경합 → 풀·DB 상한을 구분할 수 없음 | 풀 크기 판단은 그 뒤에 |
| (선택) 멱등성 선조회 트랜잭션 통합 | 결제당 약 10ms | 효과 대비 검증 비용이 큼 → 우선순위 낮음 |

### 한계

- 단계·비교 실행 모두 1회 측정 → 실행 편차는 확인하지 않음 (풀 20 재기동 직후 182.8 vs 예열 뒤 314.3처럼 상태에 따라 크게 달라짐)
- 측정 구간 60초 → 장시간 부하(Outbox 대기로 인한 알림 발생, GC·메모리 추세)는 확인하지 않음. 지속 부하의 Outbox 예상치는 계산
- 측정 중에도 Outbox 발행기·알림 Consumer·알림 발송기가 같은 커넥션 풀을 씀 → Hikari 지표에 포함됨 (운영과 같은 조건)
- 로그인은 setup에서 VU당 1번(BCrypt 비용이 흐름 수치에 섞이지 않도록) → 로그인 자체의 동시성은 측정하지 않음
- 주문은 메뉴 목록 첫 페이지의 매장 5곳에 몰림 → 매장 행은 잠그지 않으므로 영향은 없다고 판단 (확인하지 않음)
