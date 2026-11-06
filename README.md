# Spring Delivery Mini

## 1. 프로젝트 소개
- 스파르타 단기 심화 온보딩 주차 과제로, Spring Boot · JPA를 이용해 구현한 미니 배달 서비스

## 2. 프로젝트에서 요구/사용한 기술 스택
| 구분 | 기술 |
|---|---|
| 언어 · 프레임워크 | Java 21 · Spring Boot 4.1.x |
| 빌드 도구 | Gradle |
| 데이터베이스 | PostgreSQL 18 |
| 사용 기술 | Spring Web · Spring Data JPA · PostgreSQL Driver · Spring Security · Validation · Lombok · JWT (JJWT) |
| API 테스트 | Postman |
| 테스트 | JUnit 5 · Mockito · Testcontainers (PostgreSQL 18) |
| DB 마이그레이션 | Flyway |


## 3. 문서

| 문서 | 내용 |
|---|---|
| [프로젝트 구조](docs/architecture.md) | 패키지 구조, 계층별 역할, 요청 처리 흐름, 테스트 |
| [API 명세](docs/api.md) | 공통 사항, API 목록, 주문 상태 흐름, API별 요청·응답·에러 코드 |
| [데이터베이스 / ERD](docs/database.md) | Flyway 마이그레이션, ERD, 테이블 관계·상세, 시간대, 결제 연관관계 |
| [금액 처리 정책](docs/money-policy.md) | 금액 타입, 수수료 계산·반올림, 수수료율 설정 |
| [정산 기준](docs/settlement-policy.md) | 정산 대상 시점, 정산 주기, 기간 기준, 정산 금액 집계 |

### 실행 · 테스트

```bash
./gradlew bootRun   # src/main/resources/.env.example을 .env로 복사해 DB 계정 입력
./gradlew test      # Docker 실행 중이어야 함 (로컬 DB·DB 환경변수 불필요)
```

## 4. 주요 기능
### 회원
- 회원가입 및 로그인
- BCrypt를 이용한 비밀번호 암호화
- JWT 기반 인증 및 인가
- CUSTOMER / OWNER 역할(Role)에 따른 접근 권한 분리

### 매장
- OWNER의 매장 생성 (한 OWNER가 여러 매장 보유 가능)
- 매장 목록·상세 조회 (로그인 불필요)
- 매장별 주문 조회 (본인 매장만, 다른 매장 → 403)
- 소유권 판단은 `Store.isOwnedBy` 한곳에서 → 메뉴 등록·수정·삭제, 주문 처리, 매장별 주문 조회가 같은 규칙 사용

### 메뉴
- OWNER의 메뉴 등록(본인 매장에만), 수정, 삭제
- 메뉴 조회 및 상세 조회
- 메뉴 등록은 Spring Security에서 OWNER만 허용, 등록할 매장·수정·삭제할 메뉴의 매장이 본인 매장인지 Service에서 검증
- Soft Delete를 통한 메뉴 삭제 처리

### 주문
- CUSTOMER의 메뉴 주문 — 한 주문에 같은 매장의 메뉴 여러 개를 담을 수 있음 (주문 항목 `order_items`)
- 총액은 서버에서 `항목별 메뉴 가격 × 수량의 합`으로 계산
- 주문 당시 메뉴 이름·단가를 항목에 저장 → 이후 메뉴가 수정·삭제되어도 주문 내역은 그대로
- 주문은 메뉴의 매장으로 자동 연결 (`orders.store_id`), 다른 매장 메뉴를 섞으면 400
- 주문 내역 조회 (CUSTOMER는 본인 주문, OWNER는 본인의 모든 매장에 들어온 주문)
- 주문요청 상태에서만 주문 취소 가능
- OWNER의 주문 상태 변경 (결제완료 → 주문수락 → 배달완료)
- 주문자 / 매장 주인 본인 여부에 따른 접근 권한 검증

### 결제
- CUSTOMER의 본인 주문 결제
- 결제 수단 검증 (CARD만 허용)
- 결제 금액은 요청으로 받지 않고 서버에서 주문 총액을 그대로 사용
- 결제 시 플랫폼 수수료(기본 5%)와 사장님 정산액을 계산해 함께 저장 ([금액 처리 정책](docs/money-policy.md) 참고)
- 결제 완료에 따른 주문 상태 변경
- 중복 결제 방지 및 결제 완료된 주문의 취소 방지

### 정산
- 정산 단위는 매장, 배달완료(`DELIVERY_COMPLETED`)된 결제만 정산 대상
- 배달완료 시각 기준 하루 단위(Asia/Seoul, `[00:00, 다음 날 00:00)`)로 묶어 다음 날 확정 (D+1)
- 정산액은 결제마다 저장한 `settlement_amount`의 합계 (자세한 기준은 [정산 기준](docs/settlement-policy.md))

### 데이터베이스 설계
- User, Store, Menu, Order, OrderItem, Payment 간의 관계 설계
- 엔티티 간 외래키 설정
- 주문 및 결제 데이터의 연관관계 구성
- JPA를 활용한 데이터 접근 및 관리

### REST API 및 예외 처리
- Validation을 이용한 요청 데이터 검증
- 인증/인가에 따른 HTTP 상태 코드 처리
- 잘못된 요청, 권한 없음, 리소스 없음 등의 예외 처리

## 5. 질문이나 피드백 받고 싶은 것
### 질문 및 피드백 요청 사항

1. Spring Security / JWT 설계
- 현재 SecurityConfig에서 CUSTOMER / OWNER 역할별 접근 권한을 분리하고, Service에서 본인 데이터 여부를 추가로 검증하는 방식으로 구현했습니다.
  현재와 같은 역할 기반 접근 제어 구조에서 보완하거나 개선할 부분이 있을까요?
- JWT 인증 필터와 SecurityConfig의 역할 분리를 현재와 같이 구성하는 것이 적절한지 궁금합니다.
- 401(인증 실패)과 403(인가 실패)을 현재와 같이 구분해서 처리하는 방식이 적절한지 궁금합니다.

2. Soft Delete 설계
- 메뉴 삭제 시 실제 DELETE 대신 deleted = true로 처리하고 조회에서 제외하는 Soft Delete 방식을 적용했습니다.
  배달 주문 서비스에서 이 방식이 적절한지, 또는 deletedAt 등을 추가하는 것이 더 나은지 궁금합니다.
- 삭제된 메뉴와 기존 주문 데이터의 관계를 유지하기 위해 Soft Delete를 선택했는데, 이 설계에서 추가적으로 고려할 부분이 있을까요?

3. User · Menu · Order · Payment 데이터베이스 설계
- 현재 User–Menu(1:N), User–Order(1:N), Menu–Order(1:N), Order–Payment(1:1)로 관계를 설계하고 외래키를 설정했습니다.
  서비스 요구사항을 기준으로 현재 ERD와 엔티티 관계가 적절한지 검토받고 싶습니다.
- Payment.order_id에 UNIQUE 제약을 두어 주문 1건당 결제 1건만 허용하도록 했는데, 이러한 중복 결제 방지 방식을 DB 제약조건으로 처리하는 것이 적절한지 궁금합니다.
- 주문에서 total_price를 별도로 저장하여 주문 당시의 가격을 보존하도록 했는데, 메뉴 가격 변경을 고려했을 때 현재 데이터 모델이 적절한지 궁금합니다.

4. 트랜잭션 처리
- 결제 시 Payment 저장과 Order 상태 변경을 하나의 @Transactional로 묶었습니다.
  현재와 같이 결제와 주문 상태 변경을 하나의 트랜잭션으로 처리하는 것이 적절한지, 추가적으로 고려할 동시성 문제가 있는지 궁금합니다.

5. 전체적인 설계
- 현재 Controller → Service → Repository 계층으로 분리하고 DTO를 통해 요청/응답을 처리했는데, 신입 개발자 포트폴리오 수준에서 구조적으로 부족하거나 개선할 부분이 있는지 피드백 받고 싶습니다.
- 현재 구현에서 실제 운영 환경을 고려했을 때 가장 먼저 개선해야 할 부분이 무엇인지 궁금합니다.

굳이 다 답변해주실 필요는 없지만 1, 4, 5를 중점으로 피드백 받고 싶습니다.
