# 로컬 확인 가이드 — Redis · Kafka · 주문 목록 · 운영 프로필 · DB 타임아웃 · CORS · 모니터링

[← README](../README.md) · [프로젝트 구조](architecture.md)

Redis(결제 멱등성·분산 락)와 Kafka(Transactional Outbox·알림·DLT 재처리), 주문 목록 keyset 페이징·prod 프로필·DB 타임아웃(#54, 3장), CORS(#61, 4장), 모니터링 대시보드·알림 규칙 발생(#67, 5장)이 로컬에서 실제로 동작하는지 확인하는 방법.
동작 원리·장애 시 동작 표는 [프로젝트 구조](architecture.md)의 Redis 장·Kafka 장, API 형식은 [API 명세](api.md) 참고

> **자동 테스트**(1장)와 **수동 확인**(2~4장 "검증 결과")을 구분해 기록함. 수동 확인은 2-2·2-3·2-5·3장을 2026-11-18에 두 번(#54 마무리, #61 CORS 반영 후) 실행했고 결과는 각 절 끝에 있음. 3-2의 `ProdProfileGuard` 실행 순서는 2026-11-19 수정 후 다시 확인함. 2-4(DLT 재처리)는 2026-11-15에 한 번(11단계 중 10단계 통과, 1단계 미검증)

## 1. 자동 테스트 (Docker만 실행 중이면 됨)

Testcontainers가 PostgreSQL·Redis·Kafka 컨테이너를 테스트 중에 띄움 → 로컬 DB·Redis·Kafka 불필요

```bash
./gradlew test --tests '*PaymentIdempotency*'          # Redis 멱등성, Redis 장애(503), DB 기록 재전송
./gradlew test --tests '*RedisDistributedLockTest' --tests '*SettlementSchedulerLockTest'   # 분산 락
./gradlew test --tests '*OutboxIntegrationTest'        # Outbox 원자성, Kafka 접속 불가 시 동작
./gradlew test --tests '*OutboxKafkaIntegrationTest'   # 실제 Kafka 발행·수신·DLT
./gradlew test --tests '*DltReprocess*'                # DLT 재처리: 재발행·중복 실행·부분 실패·건너뜀 (#52)
./gradlew test --tests '*OutboxFollowUp*'              # V14: 발행 순서, 재발행, 알림 발송, 보관 정리, 메트릭
./gradlew test --tests '*OrderListPagingTest' --tests '*StoreOrderIntegrationTest'   # 주문 목록 keyset: 헤더·400, 중복·누락 없음·경계 (#54)
./gradlew test --tests '*ProdProfile*'                 # prod 프로필: 실제 기동 후 노출 정책, 필수 환경변수 점검 (#54)
./gradlew test --tests '*DatabaseTimeoutIntegrationTest' --tests '*ErrorResponseFormatTest'   # 커넥션 풀·잠금/문장 타임아웃 → 503 (#54)
./gradlew test --tests '*Cors*' --tests '*ProdProfile*'   # CORS: 허용·차단·헤더, 설정값 검사, prod 허용 출처 누락 시 기동 실패 (#61)
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
- 사장님 계정(V17 이후): 가입은 고객만 → `POST /api/owner-applications` 신청 → 관리자 `PATCH /api/admin/owner-applications/{id}/approve` → `POST /api/users/refresh`로 새 토큰 (가입 시 `role: OWNER`는 400). V17 이전에 만든 OWNER 계정은 그대로 사용 가능
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

#### 검증 결과 (2026-11-18, compose DB·Redis·Kafka + jar local 프로필, 포트 8090)

| 단계 | 결과 | 실제 값 |
|---|---|---|
| 1 | 통과 | 둘 다 201·같은 본문(payment id 1), 두 번째에 `Idempotent-Replayed: true` |
| 2 | 통과 | 키 1개, TTL 86399초 |
| 3 | 통과 | `DEL` 후 재전송 201 + `Idempotent-Replayed: true`, 키 다시 생성 |
| 4 | 통과 | 422 `같은 Idempotency-Key로 다른 결제 요청을 보낼 수 없습니다.` |
| 5 | 통과 | Redis 중단 상태에서 201 재전송(약 1.1초) |
| 6 | 통과 | 503, 주문 상태 `ORDER_REQUEST` 그대로 |
| 7 | 통과 | 201, 주문 `PAYMENT_COMPLETED` |

- Git Bash의 curl로 한글 본문을 보내면 400(본문 형식 오류)이 남 — 터미널 인코딩 문제이므로 확인용 값은 ASCII로 보냄

#### 재검증 결과 (2026-11-18 #61 — CORS 반영 jar, local 프로필, 포트 8090, 새 회원·매장·주문으로)

| 단계 | 결과 | 실제 값 |
|---|---|---|
| 1 | 통과 | 둘 다 201·같은 본문(payment id 9), 두 번째 `Idempotent-Replayed: true` (첫 요청 1.47초, 재전송 0.03초) |
| 2 | 통과 | 새 키 1개, TTL 86371초 (이전 검증 키 1개는 남은 TTL로 공존) |
| 3 | 통과 | `DEL` 후 재전송 201 + `Idempotent-Replayed: true`, 키 다시 생성(`EXISTS` 1) |
| 4 | 통과 | 422 `같은 Idempotency-Key로 다른 결제 요청을 보낼 수 없습니다.` |
| 5 | 통과 | Redis 중단 상태에서 201 재전송(1.06초) |
| 6 | 통과 | 503(1.04초), 주문 `ORDER_REQUEST` 그대로 |
| 7 | 통과 | 201, 주문 `PAYMENT_COMPLETED` |

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

- Git Bash에서 `docker compose exec`로 컨테이너 안 절대 경로를 실행할 때는 `MSYS_NO_PATHCONV=1`을 앞에 붙임 (붙이지 않으면 경로가 `C:/Program Files/Git/opt/...`로 바뀌어 실패)

#### 검증 결과 (2026-11-18, 2-2와 같은 환경)

| 단계 | 결과 | 실제 값 |
|---|---|---|
| 1 | 통과 | 토픽에 `PAYMENT_COMPLETED` JSON(`eventId`·`orderId`·`paymentId`·`storeId`·`customerId`·`ownerId`·`amount`·`occurredAt`) |
| 2 | 통과 | 고객·사장님 각각 알림 생성, `SENT`, 앱 로그 `알림 발송 notificationId=...` |
| 3 | 통과 | 결제 201, `PENDING` 유지·`attempt_count` 1 → 3(20초), `last_error` `KafkaException: Send failed` |
| 4 | 통과 | 재시작 후 `attempt_count` 4에서 `PUBLISHED`, 알림 2건 생성 |
| 5 | 통과 | `OUTBOX_PUBLISHER_MAX_ATTEMPTS=2`로 재기동 → 결제 약 13초 뒤 `FAILED`(`attempt_count` 2) |
| 6 | 통과 | 목록에 FAILED 1건 → 일괄 재발행 `{"republished":1}`, `PENDING`(`attempt_count` 0) → `PUBLISHED`, 알림 2건 |
| 7 | 통과 | DLT에는 2-4 검증(2026-11-15) 때 넣은 2건만 있음 — 이번 확인으로 새로 들어간 메시지 없음 |

#### 재검증 결과 (2026-11-18 #61, 2-2 재검증과 같은 환경 — 5·6단계는 `OUTBOX_PUBLISHER_MAX_ATTEMPTS=2`로 재기동)

| 단계 | 결과 | 실제 값 |
|---|---|---|
| 1 | 통과 | 토픽에 `PAYMENT_COMPLETED` JSON 1건(`orderId` 15·`paymentId` 11 등 8개 필드) |
| 2 | 통과 | 고객·사장님 알림 각 1건 `SENT`(생성 약 2초 뒤 발송), 앱 로그 `알림 발송 notificationId=...` |
| 3 | 통과 | 결제 201, 이벤트 `PENDING` — 3초 뒤 `attempt_count` 0, 23초 뒤 3, `last_error` `KafkaException: Send failed` |
| 4 | 통과 | Kafka 시작 49초 뒤 `PUBLISHED`(`attempt_count` 6, 백오프 대기 포함), 알림 2건 |
| 5 | 통과 | 결제 13초 뒤 `FAILED`(`attempt_count` 2) |
| 6 | 통과 | `GET ?status=FAILED`에 해당 이벤트(`status` FAILED·`attemptCount` 2·`lastError`), 일괄 재발행 `{"republished":1}` → `PUBLISHED`·알림 2건. 단건 재발행 `POST /{id}/republish`도 200 → `PUBLISHED`·알림 2건 |
| 7 | 통과 | DLT에는 2-4 검증 때의 키 `900`·`901` 2건만 — 새 메시지 없음 |

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
- Prometheus는 compose `app:8081`을 수집하므로 앱도 compose로 띄워야 함. 호스트 8080을 다른 프로그램이 쓰면 `APP_PORT=8090 docker compose --profile monitoring up -d --build`

#### 검증 결과 (2026-11-18, compose app + monitoring 프로필)

| 확인 | 결과 | 실제 값 |
|---|---|---|
| 수집 대상 | 통과 | `delivery-app` up |
| 규칙 | 통과 | 9개 로드, 평시 모두 inactive. `promtool` `SUCCESS: 9 rules found` |
| Redis 중단 + 키 있는 결제 | 통과 | 503, `redis_failures_total{operation="idempotency.acquire"}` 1 |
| Kafka 중단 + 결제 | 통과 | `outbox_events{status="pending"}` 1, `outbox_oldest_pending_age_seconds` 28 (약 40초 뒤) |
| 복구 후 | 통과 | pending 0, `notification_send_total{result="sent"}` 2 |
| `outbox_events{status="failed"}`·`outbox_dlt_records_total` | 미확인 | 이번에는 FAILED·DLT를 만들지 않아 0 (값 증가는 2-3 5단계·2-4에서 확인한 동작) |
- 알림이 실제로 firing되는 것(`for` 시간 경과)까지는 기다리지 않음

#### 재검증 결과 (2026-11-18 #61, `APP_PORT=8090 docker compose --profile monitoring up -d --build` — CORS 반영 이미지)

| 확인 | 결과 | 실제 값 |
|---|---|---|
| 수집 대상 | 통과 | `delivery-app` up (기동 직후 한 번은 앱이 뜨기 전이라 down → 20초 뒤 up) |
| 규칙 | 통과 | 9개 로드, 평시 모두 inactive. `promtool` `SUCCESS: 9 rules found` |
| Redis 중단 + 키 있는 결제 | 통과 | 503, `redis_failures_total{operation="idempotency.acquire"}` 1. **응답 5.26초** (jar 실행 때는 약 1초, 아래 참고) |
| Redis 실패 알림 | 통과 (이번에 처음 확인) | 약 1분 뒤 `RedisCommandFailures` **firing** (`increase(...[5m]) > 0`, `for: 1m`) |
| Kafka 중단 + 결제 | 통과 | 45초 뒤 `outbox_events{status="pending"}` 1, `outbox_oldest_pending_age_seconds` 39 |
| 복구 후 | 통과 | pending 0·failed 0, `notification_send_total{result="sent"}` 2 |
| `outbox_dlt_records_total` | 미확인 | 0 (이번에 DLT를 만들지 않음) |

- 관찰: compose 앱에서 Redis 컨테이너를 내리면 키 있는 결제의 503이 약 5초 걸림 (local jar → `localhost:6380`은 약 1초). 컨테이너가 멈추면 `redis` 호스트 이름 조회가 실패하는 경로라 Redis `connect-timeout`(1초)보다 오래 걸린 것으로 추정 — 원인은 확인하지 않음, 별도 이슈 후보

### 2-6. Slack 알림 발송 (#69)

Slack 워크스페이스에서 채널 하나에 Incoming Webhook을 만들고, URL은 **루트 `.env`에만** 넣음(`.gitignore` 대상, 코드·설정 파일·명령 기록에 남기지 않음)

```bash
# .env
NOTIFICATION_SENDER=slack
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
```

| 단계 | 하는 일 | 기대 결과 |
|---|---|---|
| 1 | 앱 기동 (jar local 프로필은 `.env`를 읽음, compose는 `.env` → `NOTIFICATION_SENDER`·`SLACK_WEBHOOK_URL` 전달) | 로그 `알림 발송기: Slack Incoming Webhook (connectTimeout=PT2S, readTimeout=PT3S)`, URL은 로그에 없음 |
| 2 | 주문 → 결제 | 5초 안에 채널에 `[배달 알림] 결제 완료 알림이 생성되었습니다. (notificationId=N)` 2건(고객·사장님), `send_status = SENT` |
| 3 | 배달 완료 처리 | `배달 완료` 알림 도착. 문구에 주문 번호·금액·user id 없음 |
| 4 | 실패 → 재시도 → FAILED: URL 끝 토큰을 일부러 바꾸고 `NOTIFICATION_DISPATCHER_MAX_ATTEMPTS=2`, `NOTIFICATION_DISPATCHER_RETRY_DELAY=10s`로 재기동 후 결제 (compose는 이 두 값을 앱에 넘기지 않음 → 아래 override 파일 사용. `.env`를 고치지 않으려면 잘못된 URL은 셸 환경변수 `SLACK_WEBHOOK_URL`로 넘김 — 셸 값이 `.env`보다 우선) | 첫 실패 `send_status = PENDING`, `send_attempt_count = 1`, 로그 `재시도 예정` → 10초 뒤 `FAILED`, `last_send_error`에 `status=4xx, body=...`(URL 없음), `notification.send{result="failed"}` 증가 |
| 5 | 미기동 확인: `NOTIFICATION_SENDER=slack`이고 `SLACK_WEBHOOK_URL`을 비운 채 기동 | 기동 실패 — `SLACK_WEBHOOK_URL이 필요합니다` (prod 프로필이면 `필수 환경변수가 없습니다: SLACK_WEBHOOK_URL`) |

```yaml
# 4단계용 임시 override (저장소 밖에 두고 docker compose -f docker-compose.yml -f <이 파일> up -d app)
services:
  app:
    environment:
      NOTIFICATION_DISPATCHER_MAX_ATTEMPTS: "2"
      NOTIFICATION_DISPATCHER_RETRY_DELAY: "10s"
```

```sql
SELECT id, event_type, send_status, send_attempt_count, last_send_error, sent_at FROM notifications ORDER BY id DESC LIMIT 10;
```
- `failed` 메트릭은 compose에서 관리 포트가 호스트에 열려 있지 않음 → `docker compose exec app wget -qO- localhost:8081/actuator/prometheus | grep notification_send`

- 끝나면 `.env`를 `NOTIFICATION_SENDER=log`로 되돌리거나 두 줄을 지움. 웹훅을 다른 사람에게 보여 줬다면 Slack 앱 설정에서 재발급

#### 검증 결과
- 자동 테스트(2026-11-20): `SlackNotificationSenderTest`(로컬 HTTP 서버로 2xx·404·503·429·응답 시간 초과·연결 거부, 가짜 HttpClient로 연결 시간 초과, Dispatcher와 묶어 재시도 → FAILED·회복 → SENT), `NotificationSenderConfigTest`(설정 분기), `ProdProfileGuardTest`
- 실제 Slack 수신(2026-11-20, compose 앱 포트 8091 — 호스트 8080·8090은 다른 프로젝트가 사용, `APP_PORT=8091`):
  - 1 통과: 기동 로그 `알림 발송기: Slack Incoming Webhook (connectTimeout=PT2S, readTimeout=PT3S)`, 앱 로그 전체에 `hooks.slack.com` 0건
    (처음엔 `.env`에 `NOTIFICATION_SENDER=slack`이 빠져 있었음 → 빠지면 기본 `log`라 Slack으로 가지 않음)
  - 2 통과: 주문 25 결제 → 약 2초 뒤 채널에 `[배달 알림] 결제 완료 알림이 생성되었습니다. (notificationId=38)`·`(notificationId=39)` 2건 수신, 두 행 `SENT`
  - 3 통과(서버 기준): 주문 25 수락 → 배달 완료 → 알림 40 `DELIVERY_COMPLETED` `SENT`
  - 4 통과: `.env`는 그대로 두고 셸 환경변수로 토큰 끝 4자를 바꾼 URL + 임시 override 파일(`NOTIFICATION_DISPATCHER_MAX_ATTEMPTS=2`·`RETRY_DELAY=10s`,
    compose 파일은 이 두 값을 넘기지 않음)로 재기동 → 주문 26 결제 → 알림 41·42 `PENDING`·1회·로그 `재시도 예정` → 10초 뒤 `FAILED`·2회,
    `last_send_error` = `Slack 비정상 응답 status=403, body=invalid_token`(URL 없음, DB·로그 모두 0건), `notification_send_total{result="failed"} 2`·`{result="retry"} 2`
  - 5 통과: `SLACK_WEBHOOK_URL=` 빈 값으로 기동 → `APPLICATION FAILED TO START`, `notification.sender=slack이면 notification.slack.webhook-url(SLACK_WEBHOOK_URL)이 필요합니다.`
  - 끝난 뒤 정상 URL로 재기동해 둠

## 3. 주문 목록 keyset · 운영 프로필 (#54)

> 2026-11-18에 아래 수동 절차를 직접 실행함 (각 절 끝의 검증 결과). 같은 내용은 자동 테스트(1장 `OrderListPagingTest`·`StoreOrderIntegrationTest`·`ProdProfile*`·`DatabaseTimeoutIntegrationTest`)로도 확인하고, 부하 측정은 [성능 개선](performance.md) 9장 절차로 실행함
> 확인 때는 호스트 8080을 다른 프로젝트가 쓰고 있어 앱을 `SERVER_PORT=8090`으로 띄움 (아래 명령의 8080을 8090으로 읽음)

### 3-1. 주문 목록 keyset 페이징

2-1처럼 앱을 띄우고 OWNER로 로그인해 토큰을 받은 뒤 (주문이 여러 건 있어야 함)

```bash
TOKEN=...   # 로그인 응답의 accessToken

# 1) 첫 페이지 — 기존 page 방식. 헤더 X-Page·X-Size·X-Has-Next + X-Next-After-Id
curl -s -D - -o /dev/null "http://localhost:8080/api/orders?size=2" -H "Authorization: Bearer $TOKEN"

# 2) 다음 페이지 — 위 X-Next-After-Id 값을 afterId로. X-Page는 오지 않음
curl -s -D - "http://localhost:8080/api/orders?afterId=<X-Next-After-Id>&size=2" -H "Authorization: Bearer $TOKEN"

# 3) 오류 — page와 afterId를 함께 보내면 400, afterId=-1도 400
curl -s "http://localhost:8080/api/orders?page=0&afterId=1" -H "Authorization: Bearer $TOKEN"
```

| 확인 | 기대 결과 |
|---|---|
| 2)를 `X-Has-Next: false`가 될 때까지 반복 | 받은 id를 이어 붙이면 `?page=0,1,2...`로 받은 결과와 같은 순서·같은 개수 (중복·누락 없음) |
| 마지막 페이지 | `X-Has-Next: false`, `X-Next-After-Id` 헤더 없음 |
| 넘기는 중간에 CUSTOMER로 새 주문 생성 | 이미 받은 주문이 다시 나오지 않고, 새 주문은 마지막 페이지에 이어서 나옴 |
| 응답 본문 | 두 방식 모두 기존과 같은 JSON 배열 |

#### 검증 결과 (2026-11-18, 주문 7건·size=2)

- 통과: page 방식 `1,2 / 3,4 / 5,6 / 7`(마지막 `X-Has-Next: false`), keyset 방식 `1,2 / 3,4 / 5,6 / 7,8` — 두 번째 페이지를 받은 뒤 새 주문 8을 결제했고, 이미 받은 주문은 다시 나오지 않고 8이 마지막 페이지에 이어서 나옴
- keyset 요청에는 `X-Page` 없음, 마지막 페이지에 `X-Next-After-Id` 없음
- `page=0&afterId=1` 400, `afterId=-1` 400

#### 재검증 결과 (2026-11-18 #61, compose 앱 포트 8090, OWNER 주문 9건·size=3)

- 통과: page 방식 `12,13,14 / 15,16,17 / 18,19,20`(마지막 `X-Has-Next: false`, `X-Next-After-Id` 없음)
- 통과: keyset 방식 `12,13,14 / 15,16,17 / 18,19,20 / 21` — 두 번째 페이지 뒤 새 주문 21을 결제, 중복 없이 마지막 페이지에 이어서 나옴. 첫 요청(afterId 없음)만 `X-Page: 0`, 이후 keyset 요청에는 없음
- 통과: `page=0&afterId=1` 400, `afterId=-1` 400

### 3-2. prod 프로필

Redis·Kafka는 compose 것을 쓰고, 앱만 jar로 prod 프로필 실행

```bash
./gradlew bootJar
docker compose up -d redis kafka

# 1) 필수 환경변수 누락 → 기동 실패 (메시지에 빠진 변수 이름만, 값은 출력되지 않음)
SPRING_PROFILES_ACTIVE=prod java -jar build/libs/Spring-Delivery-Mini-0.0.1-SNAPSHOT.jar
#   → "prod 프로필 설정 오류 — 필수 환경변수가 없습니다: DB_URL, DB_USERNAME, ..."

# 2) 정상 기동 (DB는 local 실행과 같은 localhost:5432 delivery DB)
SPRING_PROFILES_ACTIVE=prod DB_URL=jdbc:postgresql://localhost:5432/delivery DB_USERNAME=... DB_PASSWORD=... \
  JWT_SECRET=... REDIS_HOST=localhost REDIS_PORT=6380 KAFKA_BOOTSTRAP_SERVERS=localhost:9094 \
  java -jar build/libs/Spring-Delivery-Mini-0.0.1-SNAPSHOT.jar
```

| 요청 | 기대 결과 |
|---|---|
| `curl -i localhost:8080/swagger-ui.html`, `/v3/api-docs` | 404 |
| `curl -i localhost:8080/actuator/health` | 404 (서비스 포트에는 Actuator 없음) |
| `curl localhost:8081/actuator/health` | `{"status":"UP"}`만 (DB 등 상세 없음) |
| `curl -i localhost:8081/actuator/health/liveness`, `/readiness` | 200 |
| `curl -s localhost:8081/actuator/prometheus \| head` | 메트릭 출력 |
| `curl -i localhost:8081/actuator/env` | 토큰 없으면 401, ADMIN 토큰이어도 404 (엔드포인트 비활성) |
| 2)에 `SPRINGDOC_SWAGGER_UI_ENABLED=true` 추가해 실행 | 기동 실패 ("운영 프로필에서는 비활성이어야 합니다: springdoc.swagger-ui.enabled") |

- 8081(관리 포트)은 확인용으로 로컬에서만 접근. 실제 배포에서는 외부에 공개하지 않음 ([운영 프로필](operations.md))

#### 검증 결과 (2026-11-18)

| 확인 | 결과 | 실제 값 |
|---|---|---|
| 1) 필수 환경변수 전부 누락 | **기대와 다름** | 기동은 실패하지만 안내 메시지 대신 `'url' must start with "jdbc"`(DataSource 생성 실패). 값은 출력되지 않음 |
| 1') DB·JWT는 있고 `REDIS_HOST`·`KAFKA_BOOTSTRAP_SERVERS`만 누락 | 통과 | `prod 프로필 설정 오류 — 필수 환경변수가 없습니다: REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS` |
| swagger·api-docs·서비스 포트 actuator | 통과 | 모두 404 |
| 8081 health | 통과 | `{"groups":["liveness","readiness"],"status":"UP"}` — 그룹 이름만 있고 DB 등 상세 없음 |
| liveness·readiness | 통과 | 200 |
| prometheus | 통과 | 메트릭 출력 |
| `/actuator/env` | 통과 | 토큰 없음 401, ADMIN 토큰 404 |
| `SPRINGDOC_SWAGGER_UI_ENABLED=true` | 통과 | `운영 프로필에서는 비활성이어야 합니다: springdoc.swagger-ui.enabled` |

#### 재검증 결과 (2026-11-18 #61 — `CORS_ALLOWED_ORIGINS` 필수 추가 후, 서비스 포트 8091·관리 포트 8081)

| 확인 | 결과 | 실제 값 |
|---|---|---|
| 필수 환경변수 전부 누락 | **기대와 다름 (기존 문제 그대로)** → 2026-11-19 수정 후 통과 | 수정 전 `'url' must start with "jdbc"` — 아래 "발견한 문제" |
| DB·JWT만 있음 | 통과 | `필수 환경변수가 없습니다: REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS, CORS_ALLOWED_ORIGINS` |
| `CORS_ALLOWED_ORIGINS`만 누락 | 통과 | `필수 환경변수가 없습니다: CORS_ALLOWED_ORIGINS` |
| `CORS_ALLOWED_ORIGINS=*` | 통과 | 기동 실패 `cors.allowed-origins에 와일드카드(*)는 사용할 수 없습니다: *` |
| `CORS_ALLOWED_ORIGINS=https://app.example.com/` | 통과 | 기동 실패 `scheme://host[:port] 형식이어야 합니다 (경로·끝 슬래시 없이)` |
| `SPRINGDOC_SWAGGER_UI_ENABLED=true` | 통과 | `운영 프로필에서는 비활성이어야 합니다: springdoc.swagger-ui.enabled` |
| swagger·api-docs·서비스 포트 actuator | 통과 | 모두 404 |
| 8081 health·liveness·readiness·prometheus | 통과 | `{"groups":["liveness","readiness"],"status":"UP"}`, 200, 200, 메트릭 출력 |
| `/actuator/env` | 통과 | 토큰 없음 401, ADMIN 토큰 404 |
| CORS (4장과 같은 요청) | 통과 | `https://app.example.com` Preflight 200·`Access-Control-Allow-Origin` 일치, local 기본값 `http://localhost:5173`은 403, 실제 요청에 노출 헤더 포함, `Allow-Credentials` 없음 |
| 로그의 비밀 값 | 통과 | 실패 로그에 비밀번호·키 값 없음 (`grep` 0건) |

- **발견한 문제**: `ProdProfileGuard`는 일반 `@Component`라 생성 순서가 보장되지 않음 — `entityManagerFactory`가 `flyway`→`dataSource`를 먼저 만들기 때문에 `DB_URL`이 없으면 가드보다 DataSource가 먼저 실패함. 기동 실패라는 결과는 같지만 빠진 변수 목록이 한 번에 나오지 않음. → **#61에서 수정**: 가드를 빈이 아닌 `EnvironmentPostProcessor`(`META-INF/spring.factories`)로 바꿔 설정 파일·프로필 반영 직후, 빈 생성 전에 검사. 자동 테스트 `ProdProfileGuardStartupTest`. 수정 후 확인(2026-11-19, prod jar를 필수 환경변수 없이 실행): `prod 프로필 설정 오류 — 필수 환경변수가 없습니다: DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET, REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS, CORS_ALLOWED_ORIGINS`

### 3-3. compose DB 포트 · DB 타임아웃

compose `db` 컨테이너는 포트 매핑이 바뀌었으므로 **재생성 필요** (`up -d`가 설정 변경을 감지해 다시 만듦, `db-data` 볼륨은 유지)

```bash
docker compose up -d db
docker compose port db 5432        # → 127.0.0.1:5432 (0.0.0.0이 아님)
```

잠금 대기 초과 → 503 확인 (psql로 결제 완료 주문의 행을 잠근 채 점주가 같은 주문을 수락 — 상태 변경은 주문 행을 `FOR UPDATE`로 조회)

```bash
# 터미널 A — 주문 행을 잠그고 트랜잭션을 열어 둠
docker compose exec db psql -U "$DB_USERNAME" -d delivery
#   BEGIN; SELECT * FROM orders WHERE id = <주문 id> FOR UPDATE;

# 터미널 B — 같은 주문 수락 (OWNER 토큰)
curl -i -X PATCH "http://localhost:8080/api/orders/<주문 id>/status" -H "Authorization: Bearer $TOKEN" \n  -H "Content-Type: application/json" -d '{"status":"ORDER_ACCEPTED"}'

# 터미널 A — 확인 후 ROLLBACK;
```

| 확인 | 기대 결과 |
|---|---|
| `docker compose port db 5432` | `127.0.0.1:5432` |
| 터미널 B 응답 | 약 5초 뒤 **503** 공통 오류 형식, 본문에 DB 오류 메시지 없음 / 주문 상태는 그대로 |
| 터미널 A `ROLLBACK` 후 B 재요청 | 정상 처리 |
| `DB_LOCK_TIMEOUT=2s`로 앱 재기동 후 반복 | 약 2초 뒤 503 |

- 세션 타임아웃 값·이유는 [운영 프로필](operations.md) 6장
- Git Bash에서는 터미널 A 대신 `psql -c "BEGIN; SELECT ... FOR UPDATE; SELECT pg_sleep(15); ROLLBACK;"`를 백그라운드로 실행해 잠금을 잡아도 됨

#### 검증 결과 (2026-11-18)

| 확인 | 결과 | 실제 값 |
|---|---|---|
| `docker compose port db 5432` | 통과 | `127.0.0.1:5432` |
| 잠금 중 수락 | 통과 | 5.05초 뒤 503 `일시적으로 요청을 처리할 수 없습니다...`(DB 오류 메시지 없음), 주문 `PAYMENT_COMPLETED` 그대로 |
| `ROLLBACK` 후 재요청 | 통과 | 204, `ORDER_ACCEPTED` |
| `DB_LOCK_TIMEOUT=2s`(prod 프로필 실행에서 확인) | 통과 | 2.11초 뒤 503 |

#### 재검증 결과 (2026-11-18 #61 — db 컨테이너 재생성 포함)

| 확인 | 결과 | 실제 값 |
|---|---|---|
| `docker compose up -d --force-recreate db` | 통과 | 컨테이너 id 바뀜, 주문/결제 행 수 21/16 → 21/16 (`db-data` 볼륨 유지) |
| 재생성 후 포트 | 통과 | `127.0.0.1:5432` |
| 재생성 후 실행 중인 앱 | 통과 | compose 앱·prod jar 모두 재시작 없이 바로 200 (Hikari가 끊긴 커넥션을 새로 맺음) |
| 잠금 중 수락 (compose 앱, 기본 5초) | 통과 | 5.14초 뒤 503 `일시적으로 요청을 처리할 수 없습니다...`, 주문 `PAYMENT_COMPLETED` 그대로 |
| 잠금 해제 후 재요청 | 통과 | 204, `ORDER_ACCEPTED` |
| `DB_LOCK_TIMEOUT=2s` (prod jar) | 통과 | 2.05초 뒤 503, 주문 `ORDER_ACCEPTED` 그대로 (기동 직후 첫 요청은 3.05초 — 첫 요청 준비 시간 포함) |

## 4. CORS (#61)

설정 근거는 [운영 프로필](operations.md) 7장. 앱을 띄운 뒤 `Origin` 헤더를 직접 붙여 브라우저 요청을 흉내 냄 (local·dev 기본 허용 출처: `http://localhost:5173`, `http://localhost:3000`)

```bash
# 1) 허용 출처 Preflight — 토큰 없이 200, 허용 메서드·헤더·노출 헤더
curl -s -o /dev/null -D - -X OPTIONS localhost:8080/api/payments -H "Origin: http://localhost:5173" \
  -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: authorization,content-type,idempotency-key"
# 2) 허용 출처 실제 요청 — Access-Control-Allow-Origin·Expose-Headers + X-Has-Next
curl -s -o /dev/null -D - "localhost:8080/api/orders?size=1" -H "Origin: http://localhost:5173" -H "Authorization: Bearer $TOKEN"
# 3) 허용하지 않은 출처 — Preflight·실제 요청 모두 403 "Invalid CORS request"
curl -s -D - -X OPTIONS localhost:8080/api/orders -H "Origin: https://evil.example.com" -H "Access-Control-Request-Method: GET"
```

| 확인 | 기대 결과 |
|---|---|
| 1) 허용 출처 Preflight | 200, `Access-Control-Allow-Origin` = 요청 출처, `Allow-Headers`에 `authorization, content-type, idempotency-key`, `Max-Age: 3600`, `Allow-Credentials` 없음 |
| 2) 허용 출처 실제 요청 | 200, `Access-Control-Expose-Headers`에 `X-Has-Next`·`X-Next-After-Id` 등 |
| 3) 허용하지 않은 출처 | Preflight 403, 토큰이 있는 실제 요청도 403 (컨트롤러에 닿지 않음), `Access-Control-Allow-Origin` 없음 |
| 허용 출처 + 목록에 없는 요청 헤더 | Preflight 403 |
| 관리자 경로 Preflight (토큰 없음) | 200 — Preflight는 인증 전에 처리 |
| `Origin` 없는 요청 (curl·서버 간 호출) | CORS와 관계없이 기존 동작 |

#### 검증 결과 (2026-11-18)

**수동 확인** — 실제 브라우저가 아닌 curl로 `Origin`·`Access-Control-Request-*` 헤더를 붙여 확인 (브라우저 확인은 프론트엔드 연동 때)

| 환경 | 확인 | 결과 | 실제 값 |
|---|---|---|---|
| local jar (8090) | 1) 허용 출처 Preflight | 통과 | 200, `Allow-Origin: http://localhost:5173`, `Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS`, `Allow-Headers: authorization, content-type, idempotency-key`, `Max-Age: 3600`, `Allow-Credentials` 없음 |
| local jar | 2) 허용 출처 실제 요청 | 통과 | 200, `Expose-Headers: X-Has-Next, X-Next-After-Id, X-Page, X-Size, X-Trace-Id, Idempotent-Replayed`, `X-Has-Next: false` |
| local jar | 3) 허용하지 않은 출처 | 통과 | Preflight 403, 토큰 있는 실제 요청 403, 본문 `Invalid CORS request`, `Allow-Origin` 없음 |
| local jar | 목록에 없는 요청 헤더(`x-custom`) | 통과 | 403 |
| local jar | 관리자 경로 Preflight (`http://localhost:3000`, 토큰 없음) | 통과 | 200 |
| local jar | `Origin` 없는 `GET /api/stores` | 통과 | 200 |
| compose 앱 (dev, 8090) | dev 기본값 | 통과 | `http://localhost:5173` 200, `https://evil.example.com` 403 |
| prod jar (8091) | 환경변수 출처만 허용·누락/와일드카드 시 기동 실패 | 통과 | 3-2 재검증 결과 |

**자동 테스트** — 2026-11-18 통과

| 테스트 | 건수 | 확인한 내용 |
|---|---|---|
| `CorsSecurityTest` (`@WebMvcTest`) | 7 | 허용 출처 Preflight(토큰 없음·관리자 경로 포함, `Idempotency-Key`·`X-Trace-Id` 헤더), 실제 요청 노출 헤더, 허용하지 않은 출처 Preflight·실제 요청 403(컨트롤러 호출 없음), 허용하지 않은 요청 헤더 403, `Origin` 없는 요청은 기존 401, `Allow-Credentials` 없음 |
| `CorsPropertiesTest` | 4 | 공백·빈 값 정리, 미설정 시 빈 목록, 와일드카드 거부, 경로·끝 슬래시·scheme 누락 거부 |
| `ProdProfileGuardTest` | 5 (+1) | `CORS_ALLOWED_ORIGINS`가 비어 있으면 기동 실패 |
| `ProdProfileGuardStartupTest` | 2 (신규) | `DB_URL`이 비어 있으면 DB 없이도 DataSource 오류가 아닌 가드 메시지(빠진 변수 목록)로 기동 실패, prod가 아니면 검사하지 않음 |
| `ProdProfileIntegrationTest` | 6 (+1) | `test,prod`로 실제 기동 후 환경변수 출처만 허용, local 기본값은 403 |

- 수동 확인 뒤 허용 요청 헤더에 `X-Trace-Id`를 추가함 (API 명세상 클라이언트가 보낼 수 있는 헤더) → 이 항목은 자동 테스트(`CorsSecurityTest` Preflight)로만 확인

발견한 점
- 거부 응답(403) 본문은 Spring 기본 `Invalid CORS request`(text/plain) — 다른 오류처럼 `ApiErrorResponse` 형식이 아님. 브라우저 JS는 CORS로 막힌 응답을 읽을 수 없어 클라이언트 영향은 없고 curl·로그 확인 때만 보임 → 이번에는 그대로 둠

## 5. 모니터링 — 대시보드 · 알림 규칙 발생 (#67)

2-5에서 확인한 "메트릭 값이 오른다"에서 한 걸음 더: **규칙이 실제로 firing되는지**와 **대시보드 패널이 장애 구간을 보여 주는지** 확인. 메트릭·규칙 정의는 [프로젝트 구조](architecture.md) Redis 장 "관측성"·Kafka 장

```bash
APP_PORT=8090 docker compose --profile monitoring up -d --build   # Grafana http://localhost:3000 (admin / .env의 GRAFANA_ADMIN_PASSWORD)
```

### 5-1. 대시보드 구성 (`monitoring/grafana/dashboards/delivery-app.json`, 패널 18개 + 행 4개)

| 행 | 패널 | 대응 규칙 |
|---|---|---|
| 알림 규칙 상태 | 발생·대기 중인 알림 표 (`ALERTS`, 현재 시점) | 전체 |
| HTTP · DB | 요청 수·5xx 비율·평균 응답 시간·JVM 힙·HikariCP(active·pending·max)·**HTTP 503 누적**(URI별 + 커넥션 대기 초과) | — |
| Outbox · Kafka | 발행 대기·실패 건수, 가장 오래된 대기 시간(300초 점선), DLT 유입, Kafka 발행·소비 처리량, Consumer lag(1000 점선), Consumer 평균 처리 시간 | `OutboxPublishDelayed`·`OutboxEventsFailed`·`OutboxDeadLetters`·`NotificationConsumerLag` |
| 알림 발송 · Redis · 정산 스케줄러 | 알림 발송 결과, Redis 명령 실패(작업별), 스케줄러 실행 결과, 집계 시간 vs 락 TTL 절반, 락 유지 시간 초과 | `NotificationSendFailed`·`RedisCommandFailures`·`SettlementScheduler*`·`DistributedLockOverrun` |

- 패널 설명(ⓘ)에 대응 규칙과 조치(재발행 API, DLT 재처리 CLI 등)를 적어 둠
- 대시보드 파일을 고치면 Grafana가 약 10초 안에 다시 읽음 (재시작 불필요)

### 5-2. 점검에서 발견해 고친 것

| 발견 | 원인 | 조치 |
|---|---|---|
| `distributed_lock_overrun_total` 시계열이 없음 → `DistributedLockOverrun`이 **첫 초과를 놓침** | 카운터가 처음 증가할 때 생성됨. `increase()`는 직전 샘플이 없으면 첫 증가를 0으로 계산 | `SettlementScheduler`가 기동 시 `lock=settlement-daily-aggregation`을 0으로 등록 (`DistributedLock.OVERRUN_METRIC`, `SettlementSchedulerTest`) |
| `NotificationConsumerLag` 식의 `records_lag_max`가 평시 NaN | 최근 샘플 구간의 최대값이라 메시지가 없으면 NaN (대시보드에서 빈 그래프) | 파티션별 현재 값 `records_lag{topic="delivery-events"}`로 변경 |
| HTTP 503 패널(`increase`)이 장애 후에도 0 | HTTP 타이머는 URI·상태 조합의 첫 요청 때 생성 → 위와 같은 이유로 첫 1건을 놓침. 미리 등록할 수 없음 | 패널을 기동 후 누적값으로 변경 (계단이 오른 시점 = 발생 시각) |

- 같은 이유로 "요청 수"·"5xx 비율" 패널도 **처음 생긴 URI·상태 조합의 첫 요청**은 반영되지 않음 (추세 확인용이라 그대로 둠)
- 알림 규칙에 쓰는 앱 카운터(`redis.failures`·`settlement.scheduler.runs`·`notification.send`·`outbox.dlt.records`)는 원래 0으로 등록돼 있어 문제 없음

### 5-3. 장애 재현 → firing 확인

평가 주기는 Prometheus 기본값 1분(`evaluation_interval` 미지정), 수집 15초 → **발생 후 감지까지 최대 약 1분 15초 + `for`**

| 단계 | 하는 일 | 기대 결과 |
|---|---|---|
| 1 | `docker compose stop redis` → 새 주문 + `Idempotency-Key` 결제 | 503, `RedisCommandFailures{operation="idempotency.acquire"}` pending → 1분 뒤 firing |
| 2 | 2-4의 형식 오류 메시지 전송 (Git Bash는 `MSYS_NO_PATHCONV=1` 필요) | 재시도 없이 DLT, `OutboxDeadLetters` firing (`for` 없음) |
| 3 | `docker compose start redis` → `docker compose stop kafka` → 결제 | 201, 대기 300초 넘으면 `OutboxPublishDelayed` pending → 2분 뒤 firing |
| 4 | Kafka를 내린 채 계속 둠 | 재시도 10회(약 10분) 뒤 `FAILED` → `OutboxPublishDelayed` 해제, `OutboxEventsFailed` pending → 1분 뒤 firing |
| 5 | `docker compose start kafka` → 관리자 `POST /api/admin/outbox-events/republish` | `{"republished":1}`, 발행·알림 생성 후 모든 알림 해제 |

#### 검증 결과 (2026-11-20, compose app(8090) + monitoring 프로필, #67 수정 반영 이미지)

| 단계 | 결과 | 시각·실제 값 |
|---|---|---|
| 수집 대상·규칙 | 통과 | `delivery-app` up, 9개 로드·평시 inactive, `promtool` `SUCCESS: 9 rules found` |
| 대시보드 쿼리 | 통과 | Grafana API(`/api/ds/query`)로 패널 쿼리 전체 오류 없음 |
| 1 Redis 중단 | 통과 | 11:38:35 결제 503(**4.73초** — 2-5 재검증의 약 5초와 같은 현상, 원인 미확인) → 11:39:05 pending → 약 11:40:10 firing. 패널 "Redis 명령 실패" `idempotency.acquire` 1, "HTTP 503 누적" `/api/payments` 1 |
| 2 DLT | 통과 | 11:38:53 전송 → `outbox_dlt_records_total` 1 → 11:39:49 firing |
| 3 Kafka 중단 | 통과 | 11:40:26 결제 201(Outbox 이벤트 18) → 11:45:51 pending(대기 315초) → 11:47:53 firing(435초) |
| 4 FAILED | 통과 | 시도 10회 후 11:50:49 `FAILED` → `OutboxPublishDelayed` 해제, `OutboxEventsFailed` 11:51:50 firing |
| 5 복구 | 통과 | 11:52 Kafka 시작·재발행 1건 → 11:52:53 알림 0건, pending 0·failed 0, `notification_send_total{result="sent"}` 4 |

- 이번 기간 firing 이력: `RedisCommandFailures`·`OutboxDeadLetters`·`OutboxPublishDelayed`·`OutboxEventsFailed` (`count_over_time(ALERTS{alertstate="firing"}[30m])`)
- 재현하지 않은 규칙과 이유
  - `NotificationConsumerLag`: 1000건 이상 밀리게 해야 함 → #68 k6 쓰기 부하(결제 최대 314건/s)에서도 lag 최대 695건으로 발생하지 않음.
    Outbox 발행기가 약 92건/s로 앞에서 속도를 제한해 Consumer까지 밀리지 않음 — 대신 대기는 `outbox_events`에 쌓임(최대 20,857건, 가장 오래된 대기 82초 < `OutboxPublishDelayed` 300초) ([성능 개선](performance.md) 11장)
  - `NotificationSendFailed`: 기본 발송기(로그)는 실패하지 않음 → #69 Slack 발송기에서 웹훅 주소를 틀리게 해 확인
  - `SettlementSchedulerSkippedByRedis`·`SettlementSchedulerNearLockTtl`·`DistributedLockOverrun`: 스케줄러가 매일 01:10에만 실행 → 자동 테스트(`SettlementSchedulerTest`·`RedisDistributedLockTest`)로 메트릭 증가를 확인하고, 시계열이 기동 시 0으로 있는지만 수동 확인(`distributed_lock_overrun_total{lock="settlement-daily-aggregation"}` 0)
- 임계값(300초·1000건 등)은 실측 전 초기값 그대로 — 재현용으로 `for`·임계값을 줄이지 않고 기다림. 조정 근거는 #68 측정 후 기록

### 5-4. 평소 Redis · Kafka 상태 확인

| 대상 | 대시보드에서 볼 곳 | 정상 |
|---|---|---|
| 전체 | 맨 위 "발생·대기 중인 알림" 표, Prometheus `/alerts` | 비어 있음 / 모두 inactive |
| Redis | "Redis 명령 실패" | 모든 작업 0 |
| Kafka 발행 | "Outbox 이벤트", "가장 오래된 발행 대기 이벤트 경과 시간" | 대기·실패 0, 경과 시간 0 (300초를 넘으면 발행 멈춤) |
| Kafka 소비 | "DLT 유입", "알림 Consumer lag", "Kafka 발행·소비" | DLT 0, lag 0 근처 |

직접 확인 (Git Bash는 `/`로 시작하는 컨테이너 경로가 Windows 경로로 바뀌므로 `MSYS_NO_PATHCONV=1`)
```bash
docker compose exec redis redis-cli ping                                    # PONG
docker compose exec redis redis-cli --scan --pattern 'idempotency:payment:*' # 멱등성 키
docker compose exec redis redis-cli --scan --pattern 'lock:*'                # 분산 락 (평소에는 없음)

MSYS_NO_PATHCONV=1 docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --all-groups                 # LAG 0이면 다 처리
```
- 토픽 메시지·DLT 내용은 2-3·2-4의 consumer 명령, Outbox 상태는 2-3의 SQL

## 6. 사장님 실시간 주문 알림 (SSE, #76)

자동 테스트: `OwnerOrderStreamRegistryTest`, `OwnerOrderStreamIntegrationTest`(백엔드), `frontend/src/owner/orderStreamController.test.ts`(재연결·폴링·끊김 감지)

### 6-1. compose 실제 확인 (2026-11-21, 결과 통과)
compose 앱(`APP_PORT=8091`)을 새 코드로 다시 빌드. `.env`가 `NOTIFICATION_SENDER=slack`이면 확인용 결제가 Slack으로 나가므로 이번 실행만 log로 덮어씀
```bash
APP_PORT=8091 NOTIFICATION_SENDER=log docker compose up -d --build app
```
1. 사장님 `fc110847` 로그인 → `curl -N -H "Accept: text/event-stream" -H "Authorization: Bearer <Access>" http://localhost:8091/api/owner/order-events`
2. 고객 `demo70c1`로 매장 4 메뉴 주문 → 결제
3. 결과

| 확인 | 결과 |
|---|---|
| 토큰 없음 / 고객 토큰 / 없는 매장(`storeId=999999`) | 401 / 403 / 404, 본문은 공통 JSON (`Accept: text/event-stream`만 보냄) |
| 구독 응답 | 200 `text/event-stream`, `X-Accel-Buffering: no`, 바로 `event:connected` |
| 결제 → `event:order` | 주문 516 결제 후 **2.3초**에 수신 (`notificationId` 99, `type` PAYMENT_COMPLETED, `amount` 4500). Outbox 발행 주기(1s) + Kafka 소비 + 알림 저장 커밋 |
| SSE 3개 연결 유지 중 DB 커넥션 | Prometheus `hikaricp_connections_active` 60초 동안 **0** (open-in-view 제외 효과) |
| 연결 수·버림 | `owner_stream_connections` 연결 수만큼, `owner_stream_events_dropped_total` 0 |
| 클라이언트 종료 후 정리 | 프로세스 종료 후 약 80초 안에 `owner_stream_connections` 0 — 하트비트(25초) 쓰기 실패로 정리. Docker Desktop 포트 프록시가 끊김을 늦게 전달해 하트비트 1~2번이 더 걸림 |

### 6-2. 브라우저 확인 (아직 — 사용자 확인 항목)
- 사장님 홈·주문 화면 제목 옆 `실시간 연결됨` 배지, 다른 창에서 고객 결제 → 토스트 + 주문 목록 갱신
- '알림 소리 켜기' 클릭 → 확인음 → 다음 결제 때 소리
- 폴링 전환: compose app 중지(`docker compose stop app`) → 재연결 3번 실패 후 `30초마다 확인 중` → 다시 시작하면 `실시간 연결됨`으로 돌아오고 목록 갱신

## 7. 배송 주소 · 주문/결제 오류 코드 (#77)

### 7-1. 자동 테스트
- `OrderAddressMigrationTest`(V17 데이터 → V18: 한 줄 주소 유지·새 컬럼 NULL·CHECK), `DeliveryAddressTest`, `OrderAddressErrorCodeIntegrationTest`(HTTP: 주소 400·기존 주문 조회·중복 결제·취소된 주문 409 code)
- 프론트 `npm test`: `validateAddress.test.ts`, `postcode.test.ts`, `errorMessages.test.ts`

### 7-2. 브라우저 확인 (아직 — 사용자 확인 항목)
- 고객 로그인 → 메뉴 담기 → 장바구니 '주소 검색' → 검색 창이 화면 안에 열림 → 주소 선택 시 우편번호·도로명이 채워지고 상세 주소 칸으로 포커스
- 주소를 고르지 않고 '주문하기' → 주소 칸 아래 안내, 요청은 나가지 않음
- 주문 상세에 `(우편번호) 한 줄 주소` 표시, V18 이전 주문은 우편번호 없이 기존 주소 그대로
- 같은 주문을 두 탭에서 열고 한쪽에서 결제 → 다른 탭에서 '카드 결제' → "이미 결제된 주문입니다…" 안내 후 화면이 결제완료로 갱신
- 랜딩(`/`): 헤드리스 Chromium(DevTools Protocol)으로 확인함(2026-11-21, 39개 항목) — 1440·834·390px 가로 스크롤 없음·등장 요소 표시·콘솔 오류 없음(비로그인 refresh 401은 기존 동작), 헤더 스크롤 상태, 섹션 이동·포커스, 탭 클릭·화살표 키, 모바일 메뉴 열기·Esc·포커스 복귀, 동작 줄이기, CTA 이동(/signup·/stores·/login), 로그인 OWNER→/owner·CUSTOMER→/stores·ADMIN→/admin/settlements
  - 남은 수동 확인: 실제 휴대폰·Safari, 스크린리더 낭독
- 고객 매장 목록(`/stores`): 헤드리스 Chromium으로 확인함(2026-11-21, 34개 항목, 로컬 매장 5곳·메뉴 13개) — 목록 수·순서·메뉴 수·최저가가 API 값과 일치, 이름·메뉴 검색, 결과 없음·초기화, 필터 2종, 정렬 3종, 카드 → 상세 → 뒤로 시 조건 유지, 1440 4열·834 2열·390 1열, 가로 스크롤 없음, 요청 가로채기로 로딩 스켈레톤·오류 → 다시 시도·빈 목록 확인, 콘솔 오류 없음
- 개발자 도구에서 `t1.kakaocdn.net` 요청 차단 → '주소 검색' → 직접 입력 안내 후 우편번호·도로명 입력 가능
