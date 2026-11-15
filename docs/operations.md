# 운영 프로필 (#54)

[← README](../README.md)

운영 배포용 `prod` 프로필(`src/main/resources/application-prod.yml`)의 설정과 근거, 점검 결과.

## 1. 작업 전 점검 (기존 구성)

| 항목 | 기존 상태 | 판단 |
|---|---|---|
| 프로필 | `local`(기본) / `dev`(docker-compose) / `test` / `dlt-reprocess`(CLI). 운영 프로필 없음 | 기본 설정(application.yml)이 운영 기준이라 별도 프로필 없이도 Swagger 비활성·health만 노출 |
| Swagger | 기본 비활성, local·dev에서만 켬 | 유지. 다만 환경변수(`SPRINGDOC_*`)로 켜도 막을 장치가 없음 |
| Actuator | 기본 health만 노출(상세 숨김), local은 metrics·prometheus, dev는 비공개 관리 포트(8081)에 prometheus | 기본 설정으로 운영하면 prometheus를 수집할 수 없음. 노출 목록(exposure)만으로 막고 있어 설정 실수 하나로 env 등이 열릴 수 있음 |
| 보안 규칙 | `SecurityConfig`: `/actuator/health`·`/actuator/prometheus` 공개, 나머지 `/actuator/**`는 ADMIN, Swagger 경로 공개(비활성이면 404) | 변경 없음 |
| 비밀 값 | DB 계정·`JWT_SECRET`은 환경변수 필수(기본값 없음). DB URL은 프로필별, Redis·Kafka 주소는 기본값(localhost) | 운영에서 Redis·Kafka 주소를 빠뜨리면 localhost로 접속을 시도 → 기동은 되고 결제 멱등성·이벤트 발행이 뒤늦게 실패 |
| 배포 구성 | `Dockerfile`(JRE, 비 root 사용자), `docker-compose.yml`은 dev 프로필 | 이미지는 그대로 쓰고 `SPRING_PROFILES_ACTIVE=prod`만 지정. compose는 개발용이라 변경하지 않음 |

## 2. prod 프로필 설정

| 항목 | 설정 | 이유 |
|---|---|---|
| 접속 정보 | `DB_URL`, `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS` 환경변수 (기본값 없음) | 개발용 기본값(localhost)으로 조용히 뜨지 않도록 |
| 필수 값 점검 | `config.ProdProfileGuard` — `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`·`JWT_SECRET`·`REDIS_HOST`·`KAFKA_BOOTSTRAP_SERVERS` 중 비어 있는 것이 있으면 기동 실패 | Redis·Kafka는 처음 쓸 때 접속하므로 배포 시점에 드러나지 않음. 메시지에는 변수 이름만 넣고 값은 넣지 않음 |
| 그 밖의 비밀 값 | Redis 비밀번호·TLS 등은 Spring Boot 표준 환경변수(`SPRING_DATA_REDIS_PASSWORD` 등) | 설정 파일·저장소에 비밀 값을 두지 않음. `.env` 파일도 읽지 않음(local 전용) |
| Swagger | `springdoc.api-docs.enabled`·`swagger-ui.enabled: false` 명시 + 켜져 있으면 `ProdProfileGuard`가 기동 실패 | 환경변수로 잘못 켜도 API 구조가 공개되지 않도록 |
| Actuator 포트 | 관리 포트 `MANAGEMENT_PORT`(기본 8081) — 서비스 포트(8080)에는 `/actuator`가 없음 | 관리 포트는 외부에 공개하지 않고 내부 헬스체크·Prometheus만 접근 (dev와 같은 구조) |
| Actuator 노출 | `endpoints.access.default: none` + health·prometheus만 `read-only`로 켜고 노출 | 노출 목록과 별개로 엔드포인트 자체를 끔 → env·heapdump·loggers 등은 ADMIN 토큰이 있어도 404 |
| 헬스체크 | `show-details: never`, `probes.enabled: true` (`/actuator/health/liveness`·`readiness`) | DB 등 내부 구성을 숨기고, 오케스트레이터용 프로브 제공. Redis 헬스체크는 기본 설정대로 끔(Redis 장애로 인스턴스가 빠지지 않도록) |
| 오류 응답 | `server.error.include-message/stacktrace: never`, `include-exception: false` | `GlobalExceptionHandler`가 처리하지 않는 오류(`/error`)에서 내부 정보 노출 방지 |
| 종료 | `server.shutdown: graceful` (단계별 최대 30초, 기본값) | 배포·재시작 때 처리 중인 결제 요청을 끝낸 뒤 종료 |
| SQL 로그 | `spring.jpa.show-sql: false` 명시 | 조회 조건이 로그에 남지 않고 로그 양으로 성능이 떨어지지 않도록 |
| Flyway baseline | `spring.flyway.baseline-on-migrate: false` + 켜져 있으면 `ProdProfileGuard`가 기동 실패 | 기본 설정(true)은 이력 테이블 없이 테이블만 있는 DB를 V1 적용 완료로 기록하고 진행 → `DB_URL`을 잘못 지정해도 조용히 마이그레이션됨. 끄면 그런 DB에서는 기동 실패. 빈 DB(첫 배포)·이력이 있는 DB는 영향 없음 |
| 커넥션 풀·DB 타임아웃 | 모든 프로필 공통 (`application.yml`) — 6장 | 잠금·실행·커넥션 대기에 상한 |

실행 예

```bash
docker build -t delivery .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:postgresql://db.internal:5432/delivery -e DB_USERNAME=... -e DB_PASSWORD=... \
  -e JWT_SECRET=... -e REDIS_HOST=redis.internal -e KAFKA_BOOTSTRAP_SERVERS=kafka.internal:9092 \
  delivery
# 관리 포트 8081은 -p로 열지 않음 → 같은 네트워크의 Prometheus·헬스체크만 접근
```

## 3. 노출 정책 비교

| 경로 | local | dev | 기본(프로필 없음) | prod |
|---|---|---|---|---|
| Swagger UI·`/v3/api-docs` | 켬 | 켬 | 404 | 404 (켜면 기동 실패) |
| `/actuator/health` | 8080 | 8081 | 8080 | 8081 (+ liveness·readiness) |
| `/actuator/prometheus` | 8080 | 8081 | 404 | 8081 |
| `/actuator/metrics` | 8080 (ADMIN) | 404 | 404 | 404 |
| env·beans·heapdump 등 | 비노출 | 비노출 | 비노출 | 엔드포인트 비활성 (ADMIN도 404, 토큰 없으면 401) |

- 관리 포트에도 `SecurityConfig`가 그대로 적용됨 (토큰 없는 `/actuator/env` → 401, `ProdProfileIntegrationTest`로 확인)

## 4. 검증

| 테스트 | 확인한 내용 |
|---|---|
| `ProdProfileIntegrationTest` (5건) | `test,prod` 프로필로 실제 기동(임의 서비스·관리 포트) 후 HTTP 요청: 서비스 포트에 Actuator 없음(404)·API 정상, Swagger UI·명세 404, 관리 포트 health는 `UP`만(상세 없음)·liveness·readiness 200, prometheus 200, env·info·metrics·heapdump·loggers·beans·configprops는 ADMIN 토큰이어도 404·토큰 없으면 401 |
| `ProdProfileGuardTest` (4건) | 필수 환경변수가 모두 있으면 통과, 없거나 공백이면 이름만 담아 실패(값은 메시지에 없음), Swagger가 켜져 있으면 실패, Flyway 자동 baseline이 켜져 있으면 실패 |
| `DatabaseTimeoutIntegrationTest` (4건) | 풀의 커넥션에 `lock_timeout=5s`·`statement_timeout=30s` 적용, 풀 크기 10·커넥션 대기 10초, 다른 트랜잭션이 잡은 행 잠금 대기 초과 → `PessimisticLockingFailureException`, 문장 실행 초과 → `QueryTimeoutException` |
| `ErrorResponseFormatTest` (503 1건) | 위 예외와 커넥션 획득 실패가 503 공통 형식으로 응답, DB 오류 메시지는 응답에 없음 |

## 5. 한계 · 후속

- `SPRING_PROFILES_ACTIVE`를 빠뜨리면 `local`(기본 프로필)로 떠서 Swagger가 켜짐 → 배포 설정에 반드시 지정. 기본 프로필을 바꾸면 개발 PC 실행 방법이 달라져 이번에는 유지
- 프록시 뒤 배포 시 `server.forward-headers-strategy`, TLS 종료 위치, JVM 옵션은 배포 환경이 정해진 뒤 결정
- 커넥션 풀 크기·타임아웃 값은 기본값 수준의 출발점 — 실제 트래픽·인스턴스 수·DB `max_connections`로 조정 (6장)
- Flyway 도입 전부터 쓰던 DB를 prod로 올릴 일이 생기면 자동 baseline 대신 `flyway baseline -baselineVersion=1`을 한 번 직접 실행
- Alertmanager 연동·Redis 고가용성은 기존 후속 과제 그대로

## 6. 커넥션 풀 · DB 타임아웃

`application.yml`의 `spring.datasource.hikari` — 프로필과 관계없이 적용 (테스트도 같은 값으로 실행).

| 항목 | 값 | 환경변수 | 이유 |
|---|---|---|---|
| 풀 크기 `maximum-pool-size` | 10 (Hikari 기본값 명시) | `DB_POOL_SIZE` | 인스턴스 수 × 풀 크기가 PostgreSQL `max_connections`(기본 100)를 넘지 않아야 함 |
| 커넥션 대기 `connection-timeout` | 10초 (기본 30초) | - | 풀이 바닥났을 때 요청이 30초씩 매달리지 않도록. `lock_timeout`보다 길게 둬 잠금 대기 중인 요청이 먼저 정리됨 |
| `lock_timeout` | 5초 (PostgreSQL 기본: 무제한) | `DB_LOCK_TIMEOUT` | 주문·결제·매장·정산 행을 `FOR UPDATE`로 잠그는데, 상한이 없으면 긴 트랜잭션 하나가 같은 행을 건드리는 요청을 모두 붙잡고 풀까지 고갈시킴 |
| `statement_timeout` | 30초 (PostgreSQL 기본: 무제한) | `DB_STATEMENT_TIMEOUT` | 문장 1개의 실행 시간 상한(잠금 대기 포함). 트랜잭션 전체 시간은 제한하지 않음 |

- 전달 방식: pgjdbc `options`(`-c lock_timeout=... -c statement_timeout=...`) → 접속할 때 세션 값으로 지정. DB·계정 설정(`ALTER ROLE`)을 바꾸지 않아도 되고 설정 파일에서 값이 보임
- 넘으면: 그 문장만 실패하고 트랜잭션은 롤백 → `GlobalExceptionHandler`가 **503**으로 응답 (잠금 대기 초과·교착 상태, 문장 실행 초과, 커넥션 획득 실패). 데이터는 반영되지 않았으므로 클라이언트는 다시 시도하면 됨
- 스케줄러·Outbox 발행처럼 요청 밖에서 실행되는 작업은 기존 실패 처리 그대로 (정산 집계는 실패 기록 후 다음 실행에서 재시도, Outbox는 다음 주기에 다시 조회)

부작용 · 주의

- **Flyway도 같은 풀을 사용** → 마이그레이션 문장에도 두 타임아웃이 적용됨. 큰 테이블의 인덱스 생성·백필처럼 30초를 넘길 수 있는 마이그레이션은 그 파일 안에서 `SET LOCAL statement_timeout = 0;`으로 풀어야 함 ([DB 마이그레이션](database.md)). `lock_timeout`은 그대로 두는 편이 안전 (DDL이 잠금을 기다리는 동안 뒤따르는 조회까지 막는 일을 방지)
- 정상 상황에서 5초 넘게 잠금을 쥐는 트랜잭션은 없음: 가장 긴 것은 매장 1곳의 정산 집계(매장 행 잠금)이고 이때 같은 매장의 수수료율 변경·재집계가 기다림. 매장 하루치 결제가 매우 많아져 집계가 5초를 넘기면 기다리던 쪽이 503 → 그때는 값 조정
- `idle_in_transaction_session_timeout`은 적용하지 않음: Outbox 발행이 Kafka 응답을 기다리는 동안(최대 약 15초) 트랜잭션을 열어 두므로, 값을 잘못 잡으면 발행 중인 커넥션이 끊김
- 풀 크기 10은 인스턴스 1대 기준 출발점. 동시 요청이 풀보다 많으면 커넥션을 기다리다 10초 뒤 503
