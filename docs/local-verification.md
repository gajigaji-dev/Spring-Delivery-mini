# 로컬 확인 가이드 — Redis · Kafka

[← README](../README.md) · [프로젝트 구조](architecture.md)

Redis(결제 멱등성·분산 락)와 Kafka(Transactional Outbox·알림·DLT 재처리)가 로컬에서 실제로 동작하는지 확인하는 방법.
동작 원리·장애 시 동작 표는 [프로젝트 구조](architecture.md)의 Redis 장·Kafka 장, API 형식은 [API 명세](api.md) 참고

> 2장의 수동 절차는 프로젝트 설정(compose·application.yml) 기준으로 정리한 것 — 자동 테스트(1장)는 전체 통과를 확인했고, **2-4(DLT 재처리)는 직접 실행해 결과를 2-4 "검증 결과"에 남김**(11단계 중 10단계 통과, 1단계 미검증). 2-2·2-3·2-5는 아직 직접 실행하지 않음 — 처음 따라 할 때 명령·경로가 맞는지 함께 확인

## 1. 자동 테스트 (Docker만 실행 중이면 됨)

Testcontainers가 PostgreSQL·Redis·Kafka 컨테이너를 테스트 중에 띄움 → 로컬 DB·Redis·Kafka 불필요

```bash
./gradlew test --tests '*PaymentIdempotency*'          # Redis 멱등성, Redis 장애(503), DB 기록 재전송
./gradlew test --tests '*RedisDistributedLockTest' --tests '*SettlementSchedulerLockTest'   # 분산 락
./gradlew test --tests '*OutboxIntegrationTest'        # Outbox 원자성, Kafka 접속 불가 시 동작
./gradlew test --tests '*OutboxKafkaIntegrationTest'   # 실제 Kafka 발행·수신·DLT
./gradlew test --tests '*DltReprocess*'                # DLT 재처리: 재발행·중복 실행·부분 실패·건너뜀 (#52)
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
| 7 | DLT 확인 — consumer 명령의 토픽을 `delivery-events-dlt`로 | Consumer가 처리하지 못한 메시지가 있으면 보임 (재처리는 2-4) |

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic delivery-events --from-beginning
```

```sql
SELECT id, event_type, status, attempt_count, last_error FROM outbox_events ORDER BY id;
SELECT id, recipient_user_id, message, send_status, sent_at FROM notifications ORDER BY id;
```

### 2-4. Kafka — DLT 재처리 (#52)

Consumer 처리 실패 → DLT → 원인 해결 → CLI 재처리 흐름. 동작 규칙은 [프로젝트 구조 · Kafka 장 "DLT 재처리"](architecture.md#dlt-재처리-52)

준비: 2-1 방법 2로 앱을 띄워 둠(알림 Consumer는 앱이 처리) + 재처리 CLI용 jar
```bash
./gradlew bootJar
# CLI 실행 (별도 터미널, 앱은 계속 실행 중). 종료 코드를 보려면 bootRun 대신 jar로 실행
java -jar build/libs/Spring-Delivery-Mini-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,dlt-reprocess --all; echo "exit=$?"
```
- 종료 코드 `0` 성공 / `1` 재처리 실패·이미 실행 중·Redis 장애·DLT 토픽 없음 / `2` 인자 오류
- CLI는 HTTP 포트를 열지 않음 → 앱(8080)과 함께 띄워도 포트 충돌 없음
- CLI를 Ctrl+C 등으로 강제 종료하면 락(`lock:dlt-reprocess`)이 해제되지 않고 최대 30분 남음 → 다음 실행이 `exit=1`(이미 실행 중). 아래 "주의" 참고

테스트 메시지 보내기 — 키 `900`, 업무 헤더(`eventId`·`eventType`) 포함, 회원·주문 FK가 없어 임의 id로 됨 (`DELIVERY_COMPLETED` → 고객 알림 1건)
```bash
printf 'eventId:11111111-1111-1111-1111-111111111111,eventType:DELIVERY_COMPLETED\t900\t{"eventId":"11111111-1111-1111-1111-111111111111","eventType":"DELIVERY_COMPLETED","orderId":900,"storeId":1,"customerId":1,"ownerId":2,"amount":10000,"occurredAt":"2026-11-15T12:00:00"}\n' \
  | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
      --topic delivery-events --reader-property parse.headers=true --reader-property parse.key=true
```

형식 오류 메시지 (7단계) — 헤더 없이 키만
```bash
printf '901\t{not-json\n' \
  | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
      --topic delivery-events --reader-property parse.key=true
```

DLT 내용·위치 확인 (지정 재처리에 쓸 파티션·오프셋, `kafka_dlt-*` 헤더)
```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic delivery-events-dlt --from-beginning --formatter-property print.partition=true \
  --formatter-property print.offset=true --formatter-property print.key=true --formatter-property print.headers=true
```

| 단계 | 하는 일 | 기대 결과 |
|---|---|---|
| 1 | `docker compose stop db` 후 위 테스트 메시지 전송 | 앱 로그에 처리 실패 4번(첫 시도 + 재시도 3번) 후 DLT 전송 — 시도마다 DB 연결 대기(Hikari 기본 30초)가 걸려 **약 2분**. DLT에 키 `900` 메시지, `kafka_dlt-exception-cause-fqcn` 헤더에 원인 |
| 2 | `curl -s localhost:8080/actuator/prometheus \| grep outbox_dlt_records` | DLT 전송 성공 후 1 증가 |
| 3 | `docker compose start db` → CLI `--all` | `exit=0`, 로그 `DLT 재처리 완료 — 재발행 1건, 건너뜀 0건`. 원래 토픽에 같은 키·본문·업무 헤더로 다시 쌓이고 `kafka_dlt-*` 헤더는 없음 |
| 4 | 알림 확인 (아래 SQL) | `event_id = 1111...` 알림 1건, `consumed_events` 1건 |
| 5 | CLI `--all` 한 번 더 | `재발행 0건` (진행 위치가 기록돼 다시 보내지 않음) |
| 6 | 1단계 메시지의 위치로 지정 재처리 `--partition=<P> --offset=<O>` | `재발행 1건`이지만 알림은 그대로 1건 (Consumer가 `eventId`로 걸러냄, 앱 로그 `중복 무시`), 진행 위치는 그대로 |
| 7 | 위 형식 오류 메시지 전송 → CLI `--all` | 재시도 없이 바로 DLT, CLI는 `재발행 0건, 건너뜀 1건` (다시 보내도 같은 결과라 보내지 않음) |
| 8 | 동시 실행 재현: `docker compose exec redis redis-cli SET lock:dlt-reprocess manual PX 60000` → CLI `--all` | `exit=1`, 로그 `DLT 재처리가 이미 실행 중입니다`. 확인 후 `DEL lock:dlt-reprocess` |
| 9 | `docker compose stop redis` → CLI `--all` | `exit=1`, 재처리하지 않음 (락 상태를 알 수 없음). 확인 후 `docker compose start redis` |
| 10 | 인자 오류: `--partition=0`만, 없는 오프셋, 없는 파티션 | `exit=2`, 사용법 로그 |
| 11 | 재발행 실패(부분 실패): CLI 실행 중 Kafka를 내리기는 타이밍이 어려움 | 자동 테스트 `DltReprocessIntegrationTest`로 확인 (실패한 건에서 멈춤 → 재실행 시 그 건부터) |

```sql
SELECT event_id, recipient_user_id, event_type, send_status FROM notifications WHERE order_id = 900;
SELECT event_id, consumed_at FROM consumed_events WHERE event_id = '11111111-1111-1111-1111-111111111111';
```

- 진행 위치 확인: `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group dlt-reprocessor` (`CURRENT-OFFSET` = 다음에 재처리할 위치)
- 다시 처음부터 확인하려면 `--reset-offsets --to-earliest --execute --group dlt-reprocessor --topic delivery-events-dlt` (이미 처리한 메시지도 다시 보내지만 알림은 중복 없음)
- 같은 `eventId`로 다시 해 보려면 `consumed_events`·`notifications`의 해당 행을 지우거나 다른 UUID를 씀 (지우지 않으면 재처리해도 `중복 무시`)

**주의 — 강제 종료 후 남은 락**: 실행 중인 CLI를 Ctrl+C 등으로 끊으면 `finally`의 락 해제가 실행되지 않아 `lock:dlt-reprocess`가 TTL(30분)까지 남음
- 확인: `redis-cli GET lock:dlt-reprocess`(값 = 실행마다 다른 UUID), `redis-cli PTTL lock:dlt-reprocess`
- 실행 중인 CLI가 정말 없는지(java 프로세스) 확인한 뒤에만 `redis-cli DEL lock:dlt-reprocess` — 실행 중인 것을 지우면 동시 실행 방지가 깨짐
- 정상 종료(성공·실패·예외 모두)는 해제됨: AOF 기록에서 SET 뒤 DEL 확인 (아래 검증 결과)

#### 검증 결과 (2026-11-15, 로컬 compose Kafka·DB·Redis + jar 실행)

| 단계 | 결과 | 실제 값 |
|---|---|---|
| 1 | 통과 | 전송 14:05:41.150 → DLT 수신 14:07:47.782, **126.6초** (콘솔 consumer 수신 기준). 재시도 간격 실측 약 31초(14:06:14 → 14:06:45 → 14:07:16 → DLT) = DB 연결 대기 30초 + 재시도 간격 1초. DLT 파티션 0·오프셋 0, 원인 헤더 `CannotCreateTransactionException`, 업무 헤더 유지 |
| 2 | 통과 (일부 미검증) | 시작 0.0 → DLT 적재 후 1.0. 재시도 구간 중의 값은 수집하지 않았고, DLT 전송 실패 반복 시 미집계는 수동 재현 안 함(`KafkaConfigTest`로만 확인) |
| 3 | 통과 (1회 재실행) | 첫 실행은 `exit=1`(이미 실행 중) — 원인은 아래 "발견한 문제". 남은 락 정리 후 `exit=0`, 재발행 1건. 원래 토픽 오프셋 1에 키 `900`·같은 본문·헤더 `eventId`·`eventType`만(`kafka_dlt-*` 없음). 재발행 약 70ms 뒤 앱이 `알림 저장` |
| 4 | 통과 | 알림 1건(`recipient_user_id=1`, `send_status=SENT`), `consumed_events` 1건 |
| 5 | 통과 | `exit=0`, 재발행 0건. 그룹 `dlt-reprocessor` 파티션 0 `CURRENT-OFFSET 1 = LOG-END 1` |
| 6 | 통과 | `--partition=0 --offset=0` → `exit=0`, 재발행 1건. 앱 로그 offset 2 `중복 무시`, 알림 여전히 1건, 그룹 오프셋 1 유지 |
| 7 | 통과 | 재시도 없이 DLT(오프셋 1, 원인 `InvalidEventMessageException`, 전송부터 수신까지 약 6초는 대부분 콘솔 consumer 기동 시간). 카운터 2.0. CLI `exit=0`, 재발행 0건·건너뜀 1건, 그룹 오프셋 2 |
| 8 | 통과 | `exit=1` `이미 실행 중`, 수동 락 값 `manual` 그대로(남의 락을 지우지 않음) |
| 9 | 통과 | `exit=1`(13초), `Redis 장애로 실행 락을 확인할 수 없어 재처리하지 않습니다`, 재발행 0건 |
| 10 | 통과 | `--partition=0` / `--partition=0 --offset=99`(가능 범위 0~1 안내) / `--partition=9 --offset=0` 모두 `exit=2` |
| 11 | **미검증 (수동)** | 수동 재현 안 함 — `DltReprocessIntegrationTest`(자동)로만 확인 |

- 최종 상태: 카운터 2.0(DLT 적재 2건과 일치, CLI 재처리는 카운터를 바꾸지 않음), 알림 1건, 앱 ERROR 로그는 DB를 내려 둔 구간(14:06~14:09)에만 있음
- 검증 데이터(`order_id` 900 알림·`consumed_events` 1건, Kafka 토픽 메시지)는 로컬에 그대로 둠

**발견한 문제 — 이전 실행이 남긴 락**: 3단계 첫 실행이 `exit=1`(이미 실행 중)로 끝남. 실행 중인 CLI 프로세스는 없었음
- Redis AOF 기록: 이전 CLI 실행 4번(13:51~13:55)은 모두 SET 뒤 DEL이 있음. **14:03:55에 잡힌 락만 DEL이 없음**(만료 14:33:55)
- 그 시각에는 검증 세션에서 실행한 CLI가 없고 Kafka도 내려가 있었음 → 다른 곳에서 실행한 CLI가 DLT 조회 중 강제 종료된 것으로 추정 (실행 주체는 확인 못 함)
- 코드는 고치지 않음: 남은 락이 있는지 확인 → 실행 중인 프로세스가 없음을 확인 → `DEL` 후 진행. 개선 후보는 `docs/kafka-outbox.md` 7장

### 2-5. 메트릭 · 알림 규칙

```bash
docker compose --profile monitoring up   # Prometheus http://localhost:9090 (.env에 GRAFANA_ADMIN_PASSWORD 필요)
```

- Prometheus `/alerts`에서 `monitoring/alerts.yml`의 규칙 9개 상태 확인
- 2-2·2-3에서 Redis·Kafka를 내렸을 때 올라가는 값: `redis_failures_total`, `outbox_events{status="pending"}`, `outbox_oldest_pending_age_seconds`, `outbox_events{status="failed"}`
- 2-4에서 DLT로 보냈을 때: `outbox_dlt_records_total` (DLT 전송 성공 건수만)
- 알림 발송 결과: `notification_send_total{result}`
- 규칙 파일 문법 검사:
  ```bash
  docker run --rm --entrypoint promtool -v "<프로젝트 경로>/monitoring:/etc/prometheus:ro" \
    prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
  ```
