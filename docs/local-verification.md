# 로컬 확인 가이드 — Redis · Kafka

[← README](../README.md) · [프로젝트 구조](architecture.md)

Redis(결제 멱등성·분산 락)와 Kafka(Transactional Outbox·알림)가 로컬에서 실제로 동작하는지 확인하는 방법.
동작 원리·장애 시 동작 표는 [프로젝트 구조](architecture.md)의 Redis 장·Kafka 장, API 형식은 [API 명세](api.md) 참고

> 2장의 수동 절차는 프로젝트 설정(compose·application.yml) 기준으로 정리한 것 — 자동 테스트(1장)는 전체 통과를 확인했고, 수동 절차는 처음 따라 할 때 명령·경로가 맞는지 함께 확인

## 1. 자동 테스트 (Docker만 실행 중이면 됨)

Testcontainers가 PostgreSQL·Redis·Kafka 컨테이너를 테스트 중에 띄움 → 로컬 DB·Redis·Kafka 불필요

```bash
./gradlew test --tests '*PaymentIdempotency*'          # Redis 멱등성, Redis 장애(503), DB 기록 재전송
./gradlew test --tests '*RedisDistributedLockTest' --tests '*SettlementSchedulerLockTest'   # 분산 락
./gradlew test --tests '*OutboxIntegrationTest'        # Outbox 원자성, Kafka 접속 불가 시 동작
./gradlew test --tests '*OutboxKafkaIntegrationTest'   # 실제 Kafka 발행·수신·DLT
./gradlew test --tests '*OutboxFollowUp*'              # V14: 발행 순서, 재발행, 알림 발송, 보관 정리, 메트릭
./gradlew test                                         # 전체
```

- Docker Desktop이 꺼져 있으면 `Could not find a valid Docker environment`로 통합 테스트가 모두 실패함
- 결과는 `build/test-results/test/*.xml`에서 총계·실패 목록만 확인

## 2. 로컬에서 직접 확인

### 2-1. 실행

```bash
# 방법 1) 전부 compose로 — 앱(8080) + PostgreSQL + Redis(127.0.0.1:6380) + Kafka(127.0.0.1:9094)
docker compose up --build

# 방법 2) 앱만 IDE·gradle로 (local 프로필, localhost:5432에 delivery DB 필요)
docker compose up -d redis kafka
./gradlew bootRun
```

- 요청은 local·dev 프로필에서 켜지는 Swagger UI(`/swagger-ui.html`)로 보내는 것이 편함
- 관리자 API 확인에는 ADMIN 계정이 필요 — 없으면 DB에서 `users.role`을 `ADMIN`으로 바꿈
- 호스트의 6379(Redis)·9092(Kafka)는 다른 프로젝트 것일 수 있으므로 건드리지 않음

### 2-2. Redis — 결제 멱등성

| 단계 | 하는 일 | 기대 결과 |
|---|---|---|
| 1 | 회원가입·로그인 → 주문 생성 → `POST /api/payments`에 `Idempotency-Key: test-1` 헤더로 같은 요청을 두 번 | 둘 다 201·같은 본문, 두 번째에 `Idempotent-Replayed: true` |
| 2 | Redis 키 확인 (아래 명령) | `idempotency:payment:{해시}` 1개, TTL 약 24시간 |
| 3 | 그 키를 `DEL`로 지우고 같은 요청 재전송 | 여전히 201 + `Idempotent-Replayed: true` (DB 멱등성 기록으로 재전송), Redis 키가 다시 생김 |
| 4 | 같은 키로 다른 주문 결제 | 422 |
| 5 | `docker compose stop redis` 후 이미 결제한 키로 재시도 | 201 재전송 |
| 6 | Redis가 내려간 상태에서 새 주문 + 새 키 | 503 (결제 안 됨) |
| 7 | Redis가 내려간 상태에서 키 없는 결제 | 정상 201 |

```bash
docker compose exec redis redis-cli --scan --pattern 'idempotency:payment:*'
docker compose exec redis redis-cli TTL <키>
docker compose exec redis redis-cli DEL <키>
```

```sql
-- DB 멱등성 기록 (결제와 같은 트랜잭션에 저장됨)
SELECT user_id, idempotency_key, payment_id, created_at FROM payment_idempotency_keys;
```

분산 락(`lock:settlement-daily-aggregation`)은 스케줄러가 매일 01:10에만 잡으므로 수동 확인보다 자동 테스트로 확인

### 2-3. Kafka — Outbox · 알림

| 단계 | 하는 일 | 기대 결과 |
|---|---|---|
| 1 | 토픽을 읽는 consumer를 띄워 두고 결제 | `delivery-events`에 `PAYMENT_COMPLETED` JSON 한 줄 |
| 2 | `GET /api/notifications` (고객·사장님 각각) | 알림이 생기고 `sendStatus`가 `PENDING` → `SENT`, 앱 로그에 `알림 발송 notificationId=...` |
| 3 | `docker compose stop kafka` 후 결제 | 결제는 201, `outbox_events`에 `PENDING`으로 쌓이고 `attempt_count` 증가 |
| 4 | `docker compose start kafka` | 재시도 시각이 지나면 `PUBLISHED`, 알림 생성 |
| 5 | FAILED 만들기: 앱에 `OUTBOX_PUBLISHER_MAX_ATTEMPTS=2`를 주고 Kafka를 내린 채 결제 | 몇 초 뒤 `FAILED` (기본값 10번이면 약 13분) |
| 6 | Kafka를 켠 뒤 `GET /api/admin/outbox-events` → `POST /api/admin/outbox-events/republish` | `FAILED` 목록 확인 후 `PENDING`으로 돌아가 다음 주기에 발행 |
| 7 | DLT 확인 — consumer 명령의 토픽을 `delivery-events-dlt`로 | Consumer가 처리하지 못한 메시지가 있으면 보임 |

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic delivery-events --from-beginning
```

```sql
SELECT id, event_type, status, attempt_count, last_error FROM outbox_events ORDER BY id;
SELECT id, recipient_user_id, message, send_status, sent_at FROM notifications ORDER BY id;
```

### 2-4. 메트릭 · 알림 규칙

```bash
docker compose --profile monitoring up   # Prometheus http://localhost:9090 (.env에 GRAFANA_ADMIN_PASSWORD 필요)
```

- Prometheus `/alerts`에서 `monitoring/alerts.yml`의 규칙 9개 상태 확인
- 2-2·2-3에서 Redis·Kafka를 내렸을 때 올라가는 값: `redis_failures_total`, `outbox_events{status="pending"}`, `outbox_oldest_pending_age_seconds`, `outbox_events{status="failed"}`
- 알림 발송 결과: `notification_send_total{result}`
- 규칙 파일 문법 검사:
  ```bash
  docker run --rm --entrypoint promtool -v "<프로젝트 경로>/monitoring:/etc/prometheus:ro" \
    prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
  ```
