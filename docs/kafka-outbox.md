# Kafka Transactional Outbox · 알림 이벤트 (#42)

[← README](../README.md) · [프로젝트 구조 · Kafka 장](architecture.md#kafka--transactional-outbox-42) · [데이터베이스 · V13](database.md#outbox_events--발행-대기-이벤트-v13)

결제 완료·환불·배달 완료 이벤트를 Kafka로 안정적으로 발행하고, Consumer가 알림 기록을 남기도록 만든 작업 기록.
동작 방식·설정값·장애 시 동작의 기준 문서는 [프로젝트 구조 · Kafka 장](architecture.md#kafka--transactional-outbox-42)이고, 이 문서는 **왜 이렇게 만들었는지와 어떻게 검증했는지**를 남긴다.

## 1. 목표와 범위

| 구분 | 내용 |
|---|---|
| 목표 | 주요 업무 이벤트를 유실 없이 Kafka로 발행하고, Consumer에서 알림 기록을 중복 없이 저장 |
| 구현 범위 | Docker Compose에 Kafka(KRaft) 추가, Outbox 테이블(V13), 결제·환불·배달완료 트랜잭션에서 이벤트 저장, Publisher의 발행·재시도, Consumer의 알림 기록·`eventId` 멱등성, 재시도 한도 초과 메시지 DLT |
| 제약 | 기존 업무 로직·DB 정합성 유지, 업무 데이터와 Outbox는 같은 트랜잭션, Kafka 장애가 핵심 트랜잭션에 영향 없음, 정산 로직·Redis 설정 변경 없음 |
| 제외 | 실제 이메일·SMS·푸시 발송, 알림 조회 API, `FAILED` 이벤트 재발행 도구 |

## 2. 문제 → 결정

### 2-1. 업무 트랜잭션 안에서 Kafka로 바로 보내면 생기는 문제
- 보낸 뒤 DB가 롤백되면 **일어나지 않은 결제의 이벤트**가 나가고, 커밋 뒤에 보내다 실패하면 **이벤트가 유실**됨
- Kafka가 느리거나 내려가면 결제 요청까지 함께 느려지거나 실패함

**결정: Transactional Outbox**
- 업무 트랜잭션은 `outbox_events`에 INSERT만 하고 Kafka에는 접속하지 않음
- 저장은 `OutboxEventRecorder`(`Propagation.MANDATORY`)만 함 → 업무 트랜잭션 밖에서 호출하면 예외라 이벤트만 따로 커밋되는 실수가 생기지 않음
- 발행은 별도 트랜잭션의 `OutboxPublisher`가 맡음 → Kafka 장애는 발행만 늦출 뿐 결제·환불·배달완료는 그대로 성공

| 이벤트 | 저장 위치 | 알림 받는 사람 |
|---|---|---|
| `PAYMENT_COMPLETED` | `PaymentService.pay` (결제 저장 직후) | 고객·사장님 |
| `PAYMENT_REFUNDED` | `AdminPaymentService.refund` (환불 전이 직후) | 고객·사장님 |
| `DELIVERY_COMPLETED` | `OrderService.statusOrder` (배달완료 전이 직후) | 고객 |

### 2-2. 발행은 "최소 한 번"일 수밖에 없음
- Kafka 저장은 성공했지만 `PUBLISHED` 기록 전에 서버가 죽거나 응답이 시간 초과되면 다음 주기에 같은 이벤트가 다시 나감

**결정: Consumer 쪽 멱등성**
- 이벤트마다 `event_id`(UUID)를 만들어 payload·헤더에 담음
- Consumer는 `consumed_events`에 `INSERT ... ON CONFLICT DO NOTHING` → 0행이면 이미 처리한 이벤트로 보고 무시
  - JPA `save`로 넣다 PK 위반이 나면 PostgreSQL 트랜잭션 전체가 중단되므로, 충돌을 예외 없이 "0행"으로 받는 방식을 선택
- 처리 기록과 알림을 **한 트랜잭션**에 저장 → 알림 저장이 실패하면 처리 기록도 롤백되어 재시도 때 다시 처리됨
- `notifications (event_id, recipient_user_id)` UNIQUE로 DB에서 한 번 더 막음

### 2-3. 여러 인스턴스가 같은 이벤트를 동시에 보내지 않게
**결정: `FOR UPDATE SKIP LOCKED`**
- 발행 대상을 행 잠금으로 가져오고, 다른 인스턴스가 잡은 행은 건너뜀 → Redis 분산 락 없이 DB만으로 나눠 가짐
- 잠그는 것은 `outbox_events` 행뿐이라 결제·환불 트랜잭션을 기다리게 하지 않음
- 조회용 부분 인덱스 `(next_attempt_at, id) WHERE status = 'PENDING'` → 발행이 끝난 행이 쌓여도 인덱스는 대기 건수만큼만 유지

### 2-4. 발행 실패 시 재시도와 포기
- 실패할 때마다 `attempt_count`·`last_error`를 남기고 `next_attempt_at`을 지수 백오프로 미룸: min(1초 × 2^(n-1), 5분)
- 10번 실패하면(약 13분) `FAILED`로 자동 재시도 중단 → Kafka에 보낼 수 없는 상황이라 DLT 대신 DB에 남김
- 프로듀서 타임아웃을 기본값(60초·2분)보다 짧게(`max.block.ms`·`request.timeout.ms` 5초, `delivery.timeout.ms` 10초) → 장애 시 발행 batch가 행 잠금을 오래 쥐지 않음
- 같은 이유로 batch 전체에 `send-timeout`(10초)을 한 번만 적용 (건마다 적용하면 batch 크기 × timeout)

### 2-5. Consumer 처리 실패
**결정: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`**
- 1초 간격 3번 재시도 → 그래도 실패하면 `delivery-events-dlt`로 보내고 다음 메시지 처리 (실패 메시지가 파티션을 막지 않음)
- 형식 오류(JSON 오류·필수 값 누락)는 `InvalidEventMessageException`으로 바꿔 **재시도 없이** 바로 DLT
- DLT 전송까지 실패하면(Kafka 장애) 오프셋을 넘기지 않고 같은 메시지를 다시 시도 → 유실 없음

### 2-6. 그 밖의 결정
| 결정 | 이유 |
|---|---|
| 메시지 키 = 주문 id, 파티션 3 | 같은 주문의 이벤트는 같은 파티션에 순서대로 쌓임 |
| DLT 파티션 수 = 원래 토픽과 같게 | DLT는 원래 파티션 번호 그대로 보냄 |
| payload = 발생 시점 스냅샷(`DeliveryEventMessage`) | Consumer가 DB를 다시 조회하지 않고 처리 |
| V13 세 테이블은 업무 테이블에 FK 없음 | 이벤트는 독립 기록이라 주문·회원이 지워져도 남아야 하고, 업무 데이터 삭제를 막지 않도록 |
| 주기 발행은 `@Scheduled`가 아닌 전용 스레드(`OutboxPublishScheduler`) | `@EnableScheduling`이 `settlement.scheduler.enabled`에 묶여 있음 → 정산 스케줄러를 끈 인스턴스에서 발행까지 멈추지 않도록, 정산 설정은 그대로 두고 분리 |
| Kafka 포트 127.0.0.1:9094 | 다른 프로젝트 Kafka(9092)와 섞이지 않도록 (Redis 6380과 같은 이유) |
| test 프로필은 Kafka 끔 | Kafka가 필요 없는 기존 통합 테스트까지 Kafka 컨테이너를 띄우지 않도록 |

## 3. 구현 중 발견한 문제

| 문제 | 원인 | 조치 |
|---|---|---|
| DLT 테스트에서 메시지를 찾지 못함 | Spring Kafka 4의 DLT 기본 이름이 `{topic}.DLT` → `{topic}-dlt`로 바뀌어 미리 만든 토픽이 아닌 곳으로 보냄 | DLT 이름을 `delivery-events-dlt`로 맞추고, 보낼 토픽을 `KafkaConfig`에서 직접 지정 (라이브러리 기본값에 기대지 않음) |
| Kafka 접속 불가 시 발행 한 번에 최대 수백 초 | 접속할 수 없으면 `send()`가 이벤트마다 `max.block.ms`(5초)씩 멈춤 → 대기 100건이면 500초 동안 행 잠금 | 첫 `send`가 바로 실패하면 그 주기의 나머지는 보내지 않고 다음 주기로 미룸 (시도 횟수 증가 없음) |
| 원자성 테스트에서 MANDATORY 예외 연쇄 | `@Transactional` 프록시 빈을 Mockito spy로 스텁하는 순간 트랜잭션 검사가 실행됨 | spy 대신 `TransactionTemplate` 안에서 결제 후 예외를 던져 함께 롤백되는지 검증 |
| `KafkaTemplate<String, String>` spy 대상을 찾지 못함 | Boot가 등록한 빈 타입이 `KafkaTemplate<?, ?>` | 테스트에서 raw 타입으로 spy |
| 필수 값 누락 테스트가 다른 단계에서 실패 | `amount`(long) 누락이 JSON 해석 단계에서 먼저 걸림 (DLT로 간 것은 정상) | 입력에 `amount`를 넣어 필수 값 검사 단계까지 가도록 수정 |

## 4. 검증

| 요구사항 | 테스트 | 확인한 내용 |
|---|---|---|
| Outbox 원자성 | `OutboxIntegrationTest` | 결제·배달완료·환불마다 같은 트랜잭션에서 저장, 바깥 트랜잭션이 롤백되면 결제·주문 상태·이벤트 모두 롤백, 실패한 요청은 이벤트 없음, 트랜잭션 밖 저장 거부, 테이블 제약 |
| Kafka 발행 실패·재시도 | `OutboxIntegrationTest`(접속 불가), `OutboxPublisherTest`, `OutboxKafkaIntegrationTest` | 접속 불가여도 결제 성공·이벤트 `PENDING`, 재시도 시각 전에는 시도 안 함, 백오프 증가 후 한도 초과 `FAILED`, 실패 뒤 실제 Kafka로 재발행, 일부 실패·`send` 즉시 예외·시간 초과 처리 |
| 여러 인스턴스 | `OutboxIntegrationTest` | 동시에 발행해도 같은 이벤트를 두 번 시도하지 않음 |
| Consumer 중복 수신·멱등성 | `OutboxIntegrationTest`, `OutboxKafkaIntegrationTest` | 같은 이벤트를 동시·반복 처리해도 알림 1번, 실제 Kafka로 같은 메시지를 다시 보내도 알림 1번, 알림 저장 실패 시 처리 기록 롤백 |
| DLT | `OutboxKafkaIntegrationTest` | 처리 실패는 첫 시도 + 재시도 2번 후 DLT(원본·헤더 보존), 뒤 메시지는 계속 처리, 형식 오류·필수 값 누락은 처리 없이 DLT |
| 상태 전이·설정 | `OutboxEventTest`, `OutboxPropertiesTest` | `PENDING → PUBLISHED / FAILED`, 잘못된 전이 거부, 설정 검증, 백오프 계산 |
| 기존 기능 회귀 | 전체 테스트 | `PaymentServiceTest`·`OrderServiceTest`·`AdminPaymentServiceTest`에 이벤트 저장 여부 검증 추가 |

- 전체 빌드 `./gradlew clean build`: **61개 클래스, 530건 통과, 실패·건너뜀 0**
- Kafka 통합 테스트 5건은 약 25초 (첫 Consumer 그룹 참여 포함)
- compose `kafka` 서비스: 볼륨 포함 KRaft 단일 노드 정상 기동(healthy) 확인

## 5. 변경 파일

| 구분 | 파일 |
|---|---|
| 마이그레이션 | `V13__add_outbox_events_and_notifications.sql` |
| 엔티티 | `OutboxEvent`, `OutboxEventStatus`, `OutboxEventType`, `ConsumedEvent`, `Notification` |
| Repository | `OutboxEventRepository`, `ConsumedEventRepository`, `NotificationRepository` |
| 설정 | `config/OutboxProperties`, `config/KafkaConfig` |
| 이벤트 (`event/`) | `OutboxEventRecorder`, `OutboxPublisher`, `OutboxPublishScheduler`, `NotificationEventListener`, `DeliveryEventMessage`, `InvalidEventMessageException` |
| 서비스 | `NotificationService`(신규), `PaymentService`·`AdminPaymentService`·`OrderService`(이벤트 저장 한 줄씩) |
| 실행 환경 | `build.gradle`, `application.yml`, `application-test.yml`, `docker-compose.yml`, `.env.example` |
| 테스트 | 신규 `KafkaTestcontainersConfiguration`, `OutboxEventTest`, `OutboxPropertiesTest`, `OutboxPublisherTest`, `OutboxIntegrationTest`, `OutboxKafkaIntegrationTest` / 수정 `PaymentServiceTest`, `OrderServiceTest`, `AdminPaymentServiceTest` |
| 문서 | `docs/architecture.md`(Kafka 장), `docs/database.md`, `docs/erd.md`, `README.md`, 이 문서 |

## 6. 미해결 사항 → 후속 반영 (V14)
#42에서 남긴 후속 후보와 반영 결과. 상세는 [프로젝트 구조 — Kafka](architecture.md#kafka--transactional-outbox-42)

| 후속 후보 | 결과 |
|---|---|
| `FAILED` 이벤트 재발행 | 관리자 API(`/api/admin/outbox-events`)로 조회·단건/일괄 재발행 (`FAILED → PENDING`, 발행은 Publisher에 맡김) |
| 보관 정책 | `RetentionCleaner`: `PUBLISHED` 7일, `consumed_events` 30일 뒤 batch 삭제 |
| 관측성 | 발행 대기·`FAILED` 건수, 가장 오래된 대기 시간, DLT 유입, Consumer lag 메트릭 + `monitoring/alerts.yml` |
| 순서 | 같은 주문의 앞 이벤트가 `PENDING`이면 뒤 이벤트를 발행하지 않음 (`FAILED`는 막지 않음) |
| 알림 발송·조회 API | `NotificationDispatcher`(PENDING → SENT / FAILED) + 로그 발송기, `GET /api/notifications` |

남은 것은 7장(DLT 재처리, #52)에서 반영한 것을 빼면: 실제 발송 채널(이메일·SMS·푸시) 연동, Alertmanager 연동, 대용량 시 파티셔닝

## 7. DLT 재처리 · DLT 적재 카운터 (#52)

동작 규칙·사용법의 기준 문서는 [프로젝트 구조 · Kafka 장 "DLT 재처리"](architecture.md#dlt-재처리-52), 로컬 수동 절차는 [로컬 확인 가이드 2-4](local-verification.md#2-4-kafka--dlt-재처리-52)

### 7-1. 문제
- DLT로 간 메시지를 원래 토픽으로 되돌리는 수단이 없음 → 원인(DB 장애 등)을 고쳐도 알림이 영영 저장되지 않음
- `outbox.dlt.records`를 DLT 토픽을 정하는 시점(destination resolver)에 셈 → DLT 전송이 실패해 같은 메시지를 다시 시도할 때마다 또 세어 **과다 집계**, 알림 규칙(10분간 1건 이상)이 실제보다 크게 울림

### 7-2. 결정
| 결정 | 이유 |
|---|---|
| 관리자 API가 아닌 CLI(`dlt-reprocess` 프로필, `DltReprocessRunner`) | 원인을 고친 뒤 드물게 실행하는 운영 작업 → 상시 노출할 필요 없음. 웹 포트·Consumer·주기 작업을 끄고 재처리만 한 뒤 종료 코드로 결과를 알림(0/1/2) |
| 키·본문·업무 헤더 유지, `kafka_dlt-*`만 제거, 파티션은 키로 | 다시 실패하면 새 원인으로 헤더가 다시 붙고, 같은 주문 이벤트는 원래처럼 한 파티션 |
| 진행 위치 = Consumer 그룹 `dlt-reprocessor` 오프셋, 브로커 저장 확인 후 그 건만 커밋 | 별도 테이블(마이그레이션) 없이 재실행 시 이어서 처리. 커밋 전 종료로 한 번 더 보내는 경우는 Consumer 멱등성(`consumed_events`)이 최종 방어 |
| 재발행 실패 시 그 건에서 멈춤 | 뒤 건만 먼저 가면 같은 파티션(같은 주문) 순서가 뒤바뀜 |
| 시작 시점 끝 오프셋까지만 | 재처리 중 다시 실패해 DLT로 돌아온 메시지를 같은 실행에서 끝없이 반복하지 않음 |
| 형식 오류는 건너뜀 (위치는 기록) | 본문이 그대로라 다시 보내도 같은 결과 |
| Redis 분산 락, Redis 장애면 실행 안 함 | 두 실행이 같은 메시지를 보내고 진행 위치가 섞이는 것을 막음. 스케줄러와 같은 원칙(장애 시 데이터를 바꾸는 쪽으로 진행하지 않음) |
| 카운터는 `DeadLetterPublishingRecoverer.publish()`를 감싸 전송 성공 후 증가 | `publish()`는 전송 결과를 기다리고 실패면 예외 → 성공한 건만 셈 |

### 7-3. 구현 중 발견한 문제
| 문제 | 원인 | 조치 |
|---|---|---|
| 로컬 Kafka에 파티션 1개짜리 `delivery-events-dlt`가 생김 | DLT가 없을 때 CLI의 Consumer 메타데이터 조회만으로 브로커가 기본 설정으로 자동 생성 → 오류 처리기가 원래 파티션 번호(0~2)로 보내지 못하게 됨 | Consumer에 `allow.auto.create.topics=false`, 토픽이 없으면 만들지 않고 종료 코드 1 (테스트로 고정) |
| `web-application-type: none`으로 띄우면 기동 실패 | `SecurityConfig`가 웹 전용 `HttpSecurity`·`PasswordEncoder` 빈을 만듦 → 보안 설정까지 조건부로 바꿔야 함 | 웹 컨텍스트는 유지하고 `server.port: -1`(포트만 열지 않음) → 기존 설정 변경 없음 |
| 로컬 수동 검증 3단계 첫 실행이 `exit=1`(이미 실행 중) | 14:03:55에 잡힌 `lock:dlt-reprocess`가 해제되지 않고 남음 (Redis AOF: 다른 실행은 모두 SET 뒤 DEL). 실행 중인 CLI는 없었음 → 강제 종료된 실행이 남긴 것으로 추정. 강제 종료(Ctrl+C·kill)는 `finally`의 락 해제를 건너뛰어 TTL 30분 동안 재실행이 막힘 | **코드 미수정**. 프로세스가 없음을 확인 후 `DEL`, 가이드 2-4에 확인·해제 절차 기록. 개선 후보: 종료 훅에서 내 토큰일 때만 해제하거나, 짧은 TTL + 실행 중 연장 |

### 7-4. 검증
| 요구사항 | 테스트 | 확인한 내용 |
|---|---|---|
| 재처리 성공 | `DltReprocessIntegrationTest` | 처리 실패 → DLT → 원인 해결 → 재처리 → 알림 저장, 원래 토픽에 같은 키·본문·업무 헤더, `kafka_dlt-*` 없음 |
| 재처리 실패 | `DltReprocessIntegrationTest` | 다시 실패하면 DLT로 돌아오고 같은 실행에서 반복하지 않음, 다음 실행에서 새 건만 처리 |
| 중복 실행 | `DltReprocessIntegrationTest`, `DltReprocessRunnerTest` | 두 번째 전체 재처리는 0건, 지정 재처리로 같은 건을 또 보내도 알림 1번, 이미 실행 중·Redis 장애면 실행 안 함 |
| 부분 실패 | `DltReprocessIntegrationTest` | 두 번째 건 재발행 실패 → 그 건에서 멈춤(세 번째 미전송), 재실행 시 첫 건은 다시 보내지 않고 두 번째부터 |
| 건너뜀·지정·오류 | `DltReprocessIntegrationTest`, `DltReprocessRunnerTest` | 형식 오류 건너뜀, 지정한 한 건만 재발행(전체 진행 위치 유지), 없는 파티션·오프셋, DLT 토픽 없음(생성 안 함), 인자 오류 종료 코드 2 |
| 카운터 | `KafkaConfigTest`, `OutboxKafkaIntegrationTest` | 전송 실패 반복 중 0 → 성공 후 1, 실제 Kafka DLT 적재 시 1 증가 |
| 기존 흐름 | 전체 테스트 | `OutboxKafkaIntegrationTest` 포함 기존 테스트 그대로 통과 |

- 전체 테스트: **68개 클래스, 601건 통과, 실패·건너뜀 0** (2026-11-15)
- jar로 CLI 실행: `--all` 종료 코드 0(포트 열지 않음), 인자 오류 2, DLT 토픽 없음 1 확인
- 로컬 수동 시나리오(가이드 2-4, 2026-11-15): 11단계 중 1~10 통과, 11(재발행 부분 실패)은 수동 미검증(자동 테스트로만)
  - DB 장애 → DLT 적재 **126.6초** (시도 4번 × DB 연결 대기 30초 + 재시도 간격 1초 × 3), 카운터 DLT 2건 = 2.0
  - 재처리 → 약 70ms 뒤 알림 저장, 재실행 0건, 지정 재처리 중복은 `중복 무시`로 알림 1건 유지, 형식 오류 건너뜀, 락 점유·Redis 장애 `exit=1`, 인자 오류 `exit=2`
  - 재시도 구간 중 카운터 값과 DLT 전송 실패 시 미집계는 수동으로 보지 않음 (`KafkaConfigTest`로만 확인)

### 7-5. 변경 파일
| 구분 | 파일 |
|---|---|
| 재처리 | 신규 `event/DltReprocessor`, `event/DltReprocessRunner`, `application-dlt-reprocess.yml` |
| 카운터 | `config/KafkaConfig` (`deadLetterRecoverer`) |
| 테스트 | 신규 `KafkaConfigTest`, `DltReprocessRunnerTest`, `DltReprocessIntegrationTest` / 수정 `OutboxKafkaIntegrationTest` |
| 문서 | `docs/architecture.md`(Kafka 장), `docs/local-verification.md`(2-4), `README.md`, 이 문서 |
