# Spring Delivery Mini

스파르타 단기 심화 온보딩 주차 과제로 시작한 미니 배달 서비스입니다. 주문·결제부터 수수료, 일 정산, 환불·이월까지 구현했습니다.

## 기술 스택
| 구분 | 기술 |
|---|---|
| 언어 · 프레임워크 | Java 21 · Spring Boot 4.1.x |
| 데이터베이스 | PostgreSQL 18 · Flyway |
| 사용 기술 | Spring Web · Spring Data JPA · Spring Security · JWT (JJWT) · Validation · Lombok |
| 관측성 · 문서 | Spring Boot Actuator · Micrometer Prometheus · Grafana · springdoc-openapi (Swagger UI) |
| 테스트 | JUnit 5 · Mockito · Testcontainers (PostgreSQL 18) |
| 빌드 · 도구 | Gradle · Postman |

## 아키텍처

```mermaid
flowchart LR
    client([Client]) -->|Bearer JWT| sec[Spring Security<br/>JWT 필터 · URL 역할 검사]
    sec --> ctrl[Controller] --> svc[Service<br/>DB 기준 권한 재확인]
    svc --> ent[Entity<br/>상태 전이 · 수수료 규칙]
    svc --> repo[Repository<br/>JPA] --> db[(PostgreSQL)]
    sch[Scheduler<br/>매일 01:10 정산 집계] --> svc
```

자세한 구성·요청 흐름·패키지 구조는 [프로젝트 구조](docs/architecture.md)를 참고하세요.

## 주요 기능
| 영역 | 내용 |
|---|---|
| 회원 | 회원가입·로그인, BCrypt, JWT 인증, CUSTOMER / OWNER / ADMIN 역할 (ADMIN은 DB에서만 지정) |
| 매장 · 메뉴 | OWNER의 매장 생성(여러 개 가능), 메뉴 등록·수정·삭제(Soft Delete), 소유권은 `Store.isOwnedBy` 한곳에서 판단 |
| 주문 | 같은 매장의 메뉴 여러 개를 한 주문에 담음, 총액은 서버 계산, 주문 당시 메뉴 이름·단가 스냅샷, 상태 전이(결제 → 수락 → 배달완료) |
| 결제 | 서버가 정한 금액으로 결제, 중복 결제 방지(DB UNIQUE), 수수료(기본 5%, 매장별 설정 가능) 스냅샷 저장, 수락 전 취소·배달 후 관리자 환불 |
| 정산 | 배달완료 시각 기준 일 정산, 스케줄러 집계(PENDING) → 관리자 확정 → 지급, 환불은 다음 정산에서 차감하고 부족하면 이월, 사장님 정산 기록 조회 |

## 문서
| 문서 | 내용 |
|---|---|
| [프로젝트 구조](docs/architecture.md) | 아키텍처·주문~정산 흐름 다이어그램, 패키지 구조, 요청 처리 흐름, 테스트 |
| [API 명세](docs/api.md) | API 목록, 주문 상태 흐름, API별 요청·응답·에러 코드 |
| [ERD](docs/erd.md) | ERD 다이어그램, 테이블 간 관계 |
| [데이터베이스](docs/database.md) | Flyway 마이그레이션, 테이블 상세·제약조건, 시간대, 결제 연관관계 |
| [금액 처리 정책](docs/money-policy.md) | 금액 타입, 수수료 계산·반올림, 공통·매장별 수수료율 |
| [정산 기준](docs/settlement-policy.md) | 정산 기간 기준, 집계·확정·지급, 결제 취소·환불과 이월 |
| [질문 · 피드백 요청](docs/review-questions.md) | 과제 리뷰 때 받고 싶은 피드백 |

## 실행 · 테스트

### 환경변수
루트의 `.env.example`을 `.env`로 복사해 값을 채움 (`.env`는 git 제외). local 프로필과 docker-compose가 같은 파일을 읽음

| 변수 | 필수 | 설명 |
|---|---|---|
| `DB_USERNAME`, `DB_PASSWORD` | O | DB 계정 (docker-compose는 이 값으로 PostgreSQL 계정도 생성) |
| `JWT_SECRET` | O | JWT 서명 키, Base64 인코딩된 256비트 이상 (`openssl rand -base64 32`). 기본값 없음 → 없으면 기동 실패 |
| `DB_URL` | dev | JDBC URL. docker-compose가 자동 지정 |
| `APP_PORT` | - | docker-compose에서 호스트에 여는 포트 (기본 8080) |
| `GRAFANA_ADMIN_PASSWORD` | monitoring | Grafana admin 비밀번호. `--profile monitoring`에서 필수 (빈 값·`admin`이면 Grafana만 기동 실패) |

### 프로필
| 프로필 | 용도 | DB | 설정 파일 |
|---|---|---|---|
| `local` (기본) | bootRun·IDE 실행, SQL 로그 출력 | `localhost:5432/delivery` | `application-local.yml` |
| `dev` | docker-compose 실행 | `DB_URL` (compose의 `db` 컨테이너) | `application-dev.yml` |
| `test` | `./gradlew test`에서 자동 활성화, 테스트 전용 JWT 키 | Testcontainers | `src/test/resources/application-test.yml` |

### 실행
```bash
# 1) Docker Compose — 애플리케이션 + PostgreSQL 18 (http://localhost:8080)
docker compose up --build -d
docker compose down          # 종료 (DB 데이터 유지, 삭제하려면 -v)

# 1-1) 모니터링 포함 — Prometheus(http://localhost:9090) + Grafana(http://localhost:3000, admin / GRAFANA_ADMIN_PASSWORD)
#      .env에 GRAFANA_ADMIN_PASSWORD 필수 (없거나 admin이면 Grafana만 기동 실패, 기본 up에는 영향 없음)
docker compose --profile monitoring up --build -d

# 2) 직접 실행 (local) — localhost:5432에 delivery DB가 있어야 함
./gradlew bootRun

# 테스트 — Docker 실행 중이어야 함 (로컬 DB·환경변수 불필요)
./gradlew test
```
- compose의 DB 포트는 로컬 PostgreSQL(5432)과 겹치지 않도록 호스트에 열지 않음
- compose의 DB 계정은 `db-data` 볼륨이 처음 생성될 때만 만들어짐 → `.env`의 DB 계정을 바꿨다면 `docker compose down -v`로 볼륨을 지우고 다시 실행
- API 문서: local·dev 프로필에서 http://localhost:8080/swagger-ui.html (기본 설정은 비활성)
- 헬스체크 `GET /actuator/health`, 메트릭 `GET /actuator/prometheus`(local은 8080, dev는 호스트에 열지 않는 8081 관리 포트)
- 모든 응답에 `X-Trace-Id` 헤더가 붙고, 같은 값이 서버 로그 각 줄(`[traceId]`)과 에러 응답 `traceId`에 들어감
- CI: GitHub Actions(`.github/workflows/ci.yml`)가 main 대상 PR·push마다 `./gradlew build`(전체 테스트 포함) 실행
