# 남은 작업 견적 (2026-11-20)

프론트엔드 연동 전 백엔드 목록(#59·#61·#62·#63)이 끝난 뒤 남은 4건의 범위·견적·순서.
견적은 혼자 작업하는 기준의 **작업일(하루 약 6시간)** 이며, 기존 코드·문서 재사용을 전제로 함.

| 순서 | 이슈 | 작업 | 견적 | 산출물 |
|---|---|---|---|---|
| 1 | ~~#67~~ 완료 | Prometheus·Grafana 모니터링 확인 | 1 ~ 1.5일 (실제 1일 이내) | 대시보드 보강, 알림 규칙 발생 확인, `docs/local-verification.md` 5장 |
| 2 | ~~#68~~ 완료 | k6 쓰기 경로 부하 테스트 수치 | 2 ~ 3일 (실제 1일 이내) | `perf/k6/order-payment.js`, `docs/performance.md` 11장 |
| 3 | ~~#69~~ 완료 | Kafka 알림 → Slack 발송 | 1.5 ~ 2일 | `event.SlackNotificationSender`, `docs/architecture.md` Kafka 장 |
| 4 | ~~#70~~ 완료 (PR #75) | 프론트엔드 | 10 ~ 14일 | `frontend/` (고객·점주 화면) |
| | | **합계** | **약 15 ~ 20일** | |

## 순서를 이렇게 정한 이유

- **모니터링 먼저**: 대시보드에 Outbox·알림·Redis 패널이 없음(현재 HTTP·JVM·HikariCP 5개뿐) → 먼저 채워야 k6 측정 중 병목(커넥션 풀·Outbox 지연)을 같이 볼 수 있음
- **k6 다음**: 지금 수치는 목록(읽기) API뿐(#40·#54). 쓰기(주문·결제)는 Outbox 기록·멱등성 키·락이 걸려 있어 포트폴리오에서 질문받기 쉬운 부분
- **Slack은 백엔드 마지막**: 발송기 인터페이스(`NotificationSender`)가 이미 있어 구현 범위가 작음. 측정이 끝난 뒤 붙여야 k6 결과에 외부 호출 지연이 섞이지 않음
- **프론트는 마지막**: API·인증 규칙이 확정된 상태(`docs/api.md` "인증 토큰" 절)에서 시작해야 재작업이 적음. 기간이 가장 길어 중간에 백엔드가 바뀌면 손해가 큼

## 1. 모니터링 확인 (#67) — 1 ~ 1.5일

현재 상태: compose에 `prometheus`(v3.5.0)·`grafana`(12.1.0), 앱 관리 포트 8081 스크레이프, 알림 규칙 9개(`monitoring/alerts.yml`), 대시보드 `delivery-app.json` 패널 5개.

> **완료 (2026-11-20)**: 패널 5 → 18개(행 4개), 규칙 4개 firing 확인, 점검 중 발견 3건 수정 — 결과 `docs/local-verification.md` 5장

| 작업 | 견적 |
|---|---|
| Prometheus targets UP·규칙 로드 확인, 메트릭 이름이 규칙 식과 맞는지 점검 | 0.25일 |
| 대시보드 패널 추가: Outbox 대기·실패·DLT, 알림 발송 성공·실패·소비 지연, Redis 명령 실패, 스케줄러 락 | 0.5일 |
| 장애 재현으로 규칙 발생 확인: Redis 중지 → `RedisCommandFailures`, Kafka 중지 → `OutboxPublishDelayed` 등 (pending → firing) | 0.25 ~ 0.5일 |
| 결과·스크린샷 문서화 | 0.25일 |

- 위험: 규칙의 `for:`가 길면 재현에 시간이 걸림 → 확인용으로만 짧게 바꾸지 말고 기다리거나, 임계값 판단 근거를 문서에 적음
- 제외: Alertmanager 연동(남은 후속 목록 유지). 필요하면 #69 Slack 웹훅을 재사용할 수 있음

## 2. k6 쓰기 경로 부하 테스트 (#68) — 2 ~ 3일

현재 상태: `perf/k6/list-api.js`(읽기 5종), `perf/seed.sql`(30만 건), 측정 절차 `docs/performance.md` 3장.

| 작업 | 견적 |
|---|---|
| 시나리오 스크립트: 고객 로그인 → 주문 생성 → 결제(`Idempotency-Key`) | 0.5일 |
| 멱등성 시나리오: 같은 키로 동시 결제 N건 → 결제 1건·나머지 재응답 확인(정합성 check) | 0.5일 |
| 측정: 동시 사용자 단계별(예: 10·20·50) p95·처리량·오류율, 그때의 HikariCP 대기·Outbox 대기 건수 | 0.5 ~ 1일 |
| 병목 분석(EXPLAIN·락 대기) + 문서화 (`docs/performance.md` 11장, 7장 환경 차이 그대로 적용) | 0.5 ~ 1일 |

- 위험: 노트북 1대에서 앱·DB·Kafka·k6가 경합 → 절대 수치보다 단계별 추세·병목 위치를 기록
- 측정 중에는 `notification.dispatcher`를 로그 발송기로 둠 (Slack 호출 지연 배제)
- 개선이 필요한 병목이 나오면 별도 이슈로 분리 (견적에 포함하지 않음)
- 결과(2026-11-20): 처리량 상한 약 300 흐름/s(노트북 CPU 경합, 풀 20도 같음), 같은 키 동시 결제 중복 0건,
  Outbox 발행 상한 약 92건/s < 결제 속도 → 별도 이슈 후보 (`docs/performance.md` 11장 "개선 후보")

## 3. Kafka 알림 → Slack 발송 (#69) — 1.5 ~ 2일

현재 흐름: `delivery-events` → `NotificationEventListener` → `notifications`(PENDING) → `NotificationDispatcher` → `NotificationSender`(기본 로그).
→ Kafka 쪽은 그대로 두고 **발송기 구현만 추가**.

| 작업 | 견적 |
|---|---|
| `SlackNotificationSender`(Incoming Webhook, 연결·읽기 타임아웃, 비 2xx는 예외 → 기존 재시도·FAILED 재사용) | 0.5일 |
| 설정: `notification.sender=slack` + `SLACK_WEBHOOK_URL`(기본값 없음), prod에서 slack 선택 시 URL 필수(`ProdProfileGuard`) | 0.25일 |
| 테스트: 단위(MockWebServer 또는 `MockRestServiceServer`로 성공·4xx·5xx·타임아웃), 설정 분기 테스트 | 0.5일 |
| 실제 워크스페이스로 수동 확인 + 문서화 | 0.25 ~ 0.5일 |

- 정책: 알림 문구에 개인정보 없음(현재 주문 번호·금액·user id만) → 그대로 유지. 웹훅 URL은 비밀값이라 로그에 남기지 않음
- 한계: Slack은 사용자별 채널이 아니라 **운영 채널 1곳**으로 보내는 데모용 채널임을 문서에 명시. 중복 발송 가능성(발송 후 기록 전 종료)은 문구에 notification id를 넣어 식별
- 레이트 리밋(웹훅 초당 약 1건): 429는 예외로 처리해 다음 주기에 재시도, 배치 크기는 설정으로 조절

## 4. 프론트엔드 (#70) — 10 ~ 14일

연동 규칙은 `docs/api.md` 공통 사항·"인증 토큰" 절이 기준. 스택은 **React + TypeScript + Vite 제안**(CORS 기본 허용 5173과 맞음) — 확정은 이슈에서.

| 작업 | 견적 |
|---|---|
| 프로젝트 구성, API 클라이언트(Bearer, 401 `code` 분기, refresh single-flight, 앱 시작 시 refresh 1회) | 1.5 ~ 2일 |
| 인증 화면: 회원가입·로그인·로그아웃, 역할별 라우팅(`/api/users/me`) | 1일 |
| 고객: 매장·메뉴 목록 → 주문 → 결제(멱등성 키 생성) → 주문 목록(keyset)·단건, 내 결제 목록 | 3 ~ 4일 |
| 알림: 목록·안 읽은 개수·읽음 처리 | 1일 |
| 점주: 매장 주문 목록·상태 변경, 정산 내역 조회 | 2 ~ 3일 |
| 공통 오류 처리·로딩·페이징 UI, 수동 시나리오 점검, README·문서 | 1.5 ~ 3일 |

- 제외(필요 시 별도 이슈): 관리자 화면(정산 확정·지급·Outbox 재발행), 배포(다른 도메인이면 `SameSite=None` + HTTPS 필요 — CLAUDE.md 남은 항목)
- 위험: 화면 범위가 늘어나는 것. 고객 흐름 → 알림 → 점주 순으로 끝낼 때마다 동작하는 상태 유지

## 다음 작업 4건 (2026-11-21 사용자 결정)

#70 이후 순서대로, 각각 별도 이슈·브랜치.

| 순서 | 이슈 | 작업 | 견적 | 상태 |
|---|---|---|---|---|
| 1 | ~~#76~~ 완료 (PR #80) | 사장님 실시간 주문 알림 (SSE) | 1.5 ~ 2일 | 실제 1일 이내 |
| 2 | #77 | 주소·결제 예외 처리 (우편번호·도로명·상세 분리 V18, 결제 오류 코드·화면 문구) | 1 ~ 1.5일 | 구현·자동 테스트 완료 (2026-11-21), 브라우저 확인·PR 대기 |
| 3 | 미정 | 토스페이먼츠 테스트 모드 (공식 공개 테스트 키, 승인 금액 대조, 결제 키 저장 V19, 취소 연동) | 2 ~ 3일 | |
| 4 | 미정 | 소셜 로그인 (카카오 + 구글, 소셜 계정 연결 V20, 성공 시 기존 Refresh 쿠키) | 2.5 ~ 4일 | |

### #76 결과
- 산출물: `GET /api/owner/order-events`, `event.OwnerOrderStreamRegistry`, `config.OpenEntityManagerInViewConfig`, 프론트 `api/orderStream.ts`·`owner/orderStreamController.ts`
- 견적에 없던 일: open-in-view 커넥션 점유 발견·수정, SSE 요청의 오류 응답 500 수정, graceful shutdown 지연 수정 (`docs/architecture.md` 실시간 주문 알림 장)
- 남은 것: 다중 인스턴스 브로드캐스트(Redis Pub/Sub) — 후속 과제로 문서화만

### #77 결과
- 주소: V18(`zip_code`·`road_address`·`detail_address`, 기존 주문은 NULL — 백필 안 함), `entity.DeliveryAddress`(@Embeddable, 생성 시 검증 → 400 `INVALID_ADDRESS`), `delivery_address`는 한 줄 주소로 계속 채움 → 기존 조회·화면 영향 없음
- 오류 코드: `exception.ErrorCode`에 주문·결제 코드 추가, `Invalid*StatusException`에 code (`docs/api.md` 공통 사항 표)
- 프론트: `components/AddressFields`(다음 우편번호 embed, 실패 시 직접 입력), `address/validateAddress`, `api/errorMessages`(code별 문구), 이미 결제·취소된 주문 409 시 다시 불러오기
- 남은 것: 브라우저에서 실제 우편번호 검색 확인
