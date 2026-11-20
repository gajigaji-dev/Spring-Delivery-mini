# 목록 API 성능 개선 · 부하 테스트 (#40)

[← README](../README.md)

목록 API 4개(매장·메뉴·주문·매장별 주문)를 페이지 단위로 바꾸고, 주문 목록의 N+1 조회와 인덱스를 개선한 기록.
개선 전후를 같은 데이터·같은 부하(k6)로 측정해 비교함.

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
