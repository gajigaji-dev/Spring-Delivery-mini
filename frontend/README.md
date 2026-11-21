# Frontend (#70)

React 19 + TypeScript + Vite 7 + React Router 7. 디자인은 **점주 콘솔 V3**(2026-11-21, claude.ai 디자인 캔버스 — 로그인·홈·주문·정산 + 디자인 시스템) 기준.

화면 스크린샷(로그인·사장님 정산·가짜 PG 결제창·고객 주문 상세)은 루트 [README "화면"](../README.md#화면), 파일은 `docs/images/`.

## 실행
```bash
cp .env.example .env      # VITE_API_BASE_URL (기본 http://localhost:8090, compose 앱이면 8091)
npm install
npm run dev               # http://localhost:5173 고정 — 백엔드 CORS 기본 허용 출처
npm test                  # vitest (API 클라이언트 인증 처리, 사장님 화면 계산 함수)
npm run build             # tsc 타입 검사 + 프로덕션 빌드
```

## 구조
| 경로 | 내용 |
|---|---|
| `src/api/client.ts` | fetch 래퍼: Bearer 헤더, 오류 → `ApiError`(status·code·traceId), 목록 헤더(`X-Has-Next`·`X-Next-After-Id`) 파싱, 401 `TOKEN_EXPIRED`만 재발급 후 1번 재시도 |
| `src/api/endpoints.ts` · `types.ts` | 화면에서 쓰는 API와 응답 타입 (기준 `docs/api.md`) |
| `src/api/errorMessages.ts` | 오류 코드(`code`)별 사용자 문구(`userMessage`) — `ErrorMessage`·토스트가 사용. 매핑 없으면 서버 `message` (#77) |
| `src/address/` | 다음 우편번호 스크립트 지연 로딩(`postcode.ts`, '주소 검색'을 누를 때 처음 1번), 주소 검증(`validateAddress.ts`, 서버 `DeliveryAddress`와 같은 규칙) (#77) |
| `src/auth/` | Access Token 메모리 보관(`tokenStore`), 앱 시작 시 refresh 1번, 역할별 화면 가드(`RequireRole`, 최종 판단은 서버 403) |
| `src/cart/` | 장바구니 — 한 매장 메뉴만, 같은 메뉴는 합침 (서버 400 규칙과 동일) |
| `src/routes/pages.ts` | 화면별 지연 로딩 + 미리 받기(`preloadPage`, `preloadWhenIdle`) |
| `src/components/` | `Layout`(헤더 알약 메뉴·매장 전환·계정 메뉴, 960px 이하 하단 탭바), `AuthShell`(로그인·가입 화면), `BootScreen`, `Toast`, `Icons`(인라인 SVG), `PrefetchLink`, `DelayedSpinner`(0.4초 지연), `Money`·`won`(₩, tabular-nums), `ErrorMessage`(문의 코드 = traceId), `Pager`, `AddressFields`(우편번호·도로명은 검색 결과로, 상세만 입력) |
| `src/owner/` | `OwnerContext`(내 매장·선택 매장·주문 목록·수락/배달 완료), `useStoreSettlements`(선택 매장 최근 n일 정산), `derive.ts`(오늘 집계·14일 매출·주간 묶음·CSV — 순수 함수, 테스트 대상) |
| `src/pages/` | 랜딩(`/`, 사장님 소개) / 고객: 매장(`/stores`)·매장 상세·장바구니·주문(keyset)·주문 상세(결제·취소)·결제 내역·사장님 전환 신청(내 정보) / 사장님: 홈(`/owner`)·주문 관리(`/owner/orders`)·정산(`/owner/settlements`)·매장 정보·메뉴 등록(`/owner/stores`) / 관리자: 정산 확정·지급, 사장님 전환 승인·거절, Outbox 재발행 / 공통: 로그인·가입(항상 고객)·알림·내 정보 |
| `src/styles/` | `tokens.css`(디자인 토큰), `base.css`(공통 컴포넌트 + 고객·관리자 화면이 쓰는 기존 클래스), `owner.css`(사장님 화면·로그인), `fonts.ts`(웹폰트, 첫 렌더 뒤 로드) |

### 디자인 적용 (점주 콘솔 V3)
- 토큰: 오트 배경 `#F6F4EF` → 흰 면 → 연한 상태 면, 토마토(`#FF5B37`·버튼 `#CF3E1E`)는 "지금 해야 할 일"에만, 잉크 다크 패널(돈)은 화면당 하나. 라이트 전용(다크 모드 없음)
- 상태 칩: 수락 대기(`PAYMENT_COMPLETED`)·배달 중(`ORDER_ACCEPTED`)·배달 완료·취소·결제 대기(`ORDER_REQUEST`, 사장님은 처리 불가) / 정산 예정(`PENDING`)·입금 대기(`CONFIRMED`)·지급 완료(`PAID`)
- 로그인 후 역할별 첫 화면: 고객 `/stores`, 사장님 `/owner`, 관리자 `/admin/settlements` (막혔던 화면이 있으면 그리로)
- 고객·관리자 화면은 별도 디자인이 없어 구조는 그대로 두고 공통 클래스(`btn`·`card`·`badge`·표)를 디자인 시스템 모양으로 바꿈
- 디자인과 다른 점(백엔드 기준): **지급 실패** 상태 없음(정산 상태는 3개) → 정산 예정 카드는 확정 전·입금 대기 2칸. 지급일 "D+2" 대신 실제 `paidAt`. 수수료율은 매장 요율 API가 없어 기간 합계(수수료 ÷ 매출)로 표시. 환불 차감·이월이 있으면 계산 블록 아래·전표에 별도 표시. 테스트 계정 버튼은 `VITE_DEMO_ACCOUNTS`가 있을 때만(계정을 소스에 넣지 않음). 서비스명은 `Delivery mini`
- 사장님 홈 계산: 오늘 매출 = 오늘 생성된 주문 중 결제 완료·배달 중·배달 완료 합계(결제 전·취소 제외), 14일 매출도 같은 기준(주문 생성일). 정산 예정액 = 최근 14일 `PENDING` 정산액 합

### 랜딩 (`/`, #77 브랜치에서 추가)
- 공식 홈페이지: 헤더(스크롤 시 배경, 860px 이하 햄버거 메뉴) → 첫 화면(고객 결제 + 사장님 콘솔 목업) → 서비스 소개(기존 → 개선 비교) → 주요 기능(고객·사장님 탭, 기능마다 화면 목업) → 주문·결제·정산 흐름 → 이용 안내(고객·사장님 단계) → 마무리 CTA → 바닥글
- 비로그인만 표시, 로그인 사용자는 역할별 첫 화면(`ROLE_HOME`)으로 이동(세션 복구 중 `BootScreen`). 매장 목록은 `/stores`. 인증 로직은 그대로
- 내용 원칙: 실제 구현된 기능만, 통계·후기·회사 정보·약관 링크 없음(약관·개인정보 처리방침 페이지가 생기면 바닥글에 추가). 목업 금액·매장명은 예시, 알림 문구는 서버 `NotificationService`와 같은 형식, 상태 문구는 `format.tsx`와 같음
- 구조: `landing/LandingHeader.tsx`(메뉴·섹션 이동 — 주소를 바꾸지 않고 스크롤 + 섹션으로 포커스), `landing/mockups.tsx`, `landing/Reveal.tsx`·`landing/motion.ts`(공유 IntersectionObserver 등장, 카운트업, 화면별 title·description), 스타일 `styles/landing.css`(랜딩 청크에서만 로드: CSS 26 kB·JS 26 kB, gzip 6·8 kB)
- 애니메이션 라이브러리는 추가하지 않음(Framer Motion 미설치 — 등장·순차·카운트업은 CSS 전환으로 충분). `prefers-reduced-motion`이면 진입·등장·반복 애니메이션 없이 최종 상태
- 접근성: 본문 건너뛰기 링크, h1 1개 + 섹션 h2·h3, 탭은 WAI-ARIA Tabs(화살표·Home·End), 모바일 메뉴 `aria-expanded`·Esc 닫기·포커스 복귀·닫힘 시 `inert`, 목업은 `role="img"` + 설명, 포커스 테두리 `:focus-visible`, 주요 터치 영역 44px 이상
- SEO: `index.html` 기본 title·description·Open Graph(`og:image`·`og:url`은 공유 이미지·배포 도메인이 정해지면 추가), 파비콘 `public/favicon.svg`(기존 로고). 랜딩을 떠나면 title은 `Delivery mini`로 바뀜 — 다른 화면별 title은 아직 없음
- 디자인 캔버스에 시안이 없어 디자인 시스템(토큰·칩·버튼)으로 구현

### 고객 매장 목록 (`/stores`, #77 브랜치에서 개선)
- 카드 그리드(모바일 1열 가로형 카드 · 600px 2열 · 1024px 3열 · 1280px 4열), 검색(매장 이름 + 메뉴 이름, 공백·대소문자 무시), 필터(전체·주문 가능·신규 매장), 정렬(등록순·최신 등록순·이름순·메뉴 많은 순), 조건 초기화, 로딩 스켈레톤·매장 없음·결과 없음·오류 + 다시 시도
- 조건은 URL 쿼리(`?q=&filter=&sort=`, 기본값은 생략)에 둬 매장 상세에서 뒤로 와도 유지. 입력은 `replace`로 방문 기록을 쌓지 않음
- **표시하는 정보는 API에 있는 것만**: 매장 이름·등록일(30일 이내 '신규'), 메뉴 API로 계산한 메뉴 수·최저가·대표 메뉴 3개. 카테고리·이미지·영업 상태·주소·평점·배달 시간은 서버에 없어 표시하지 않음 → 이미지 자리는 이름 첫 글자 + 브랜드 톤 기본 커버(`stores/StoreCover.tsx`, id로 색 고정)
- 서버에 검색·정렬·매장별 메뉴 필터가 없어 매장·메뉴를 끝까지 받아 프론트에서 처리(`stores/loadCatalog.ts`, 각 100건 × 최대 10페이지). 매장이 상한을 넘으면 안내, 메뉴가 상한을 넘거나 실패하면 메뉴 정보·메뉴 검색·메뉴 기반 필터/정렬을 빼고 보여 줌(일부 메뉴로 만든 수치는 틀리므로)
- 순수 로직은 `stores/storeCatalog.ts`(테스트 `storeCatalog.test.ts`), 스타일 `styles/stores.css`(이 화면 청크에서만 로드)

### 사장님 승인제 (V17)
- 가입 화면에 역할 선택 없음 — 서버가 고객으로만 가입시킴 (`role: OWNER`는 400)
- 고객이 '내 정보'에서 상호로 신청 → 관리자 '사장님 승인' 화면에서 승인·거절(사유)
- 승인돼도 지금 Access Token은 `role=CUSTOMER` → '사장님 화면으로 전환' 버튼이 `refresh` 후 `/me`를 다시 받아 OWNER 메뉴로 바뀜
- 거절 사유 입력은 임시로 `window.prompt` — 디자인(V3)에 관리자 화면·모달이 없어 그대로 둠

## 연동 규칙 (docs/api.md "인증 토큰")
- Access Token은 메모리에만 — localStorage 금지. 새로고침 후 `POST /api/users/refresh`로 복구
- `login`·`refresh`·`logout`만 `credentials: 'include'`
- 재발급은 single-flight(탭 안: 진행 중 Promise 공유, 탭 사이: Web Locks) — 같은 Refresh Token 두 번 사용 = 재사용 감지로 로그인 전체 폐기
- 401 분기는 `code`로: `TOKEN_EXPIRED`만 재발급, 나머지는 토큰 삭제 → 로그인 화면
- 결제는 주문별 `Idempotency-Key`를 sessionStorage에 두고 재시도 시 재사용, 성공하면 삭제
  - 409 `ORDER_ALREADY_PAID`·`ORDER_ALREADY_CANCELLED`(다른 탭에서 이미 처리)도 키를 버리고 주문을 다시 불러옴. `PAYMENT_IN_PROGRESS`·503은 키를 유지(같은 키로 재시도해야 이중 결제가 안 됨)
- 오류 문구는 `code`로 정함(`api/errorMessages.ts`) — 서버 `message` 문구로 분기하지 않음. 코드 표는 `docs/api.md` 공통 사항 "주문·결제 오류 코드"
- 주문 주소는 우편번호·도로명·상세로 나눠 보냄(#77). 화면 표시는 기존 주문 호환을 위해 한 줄 `deliveryAddress`
- 장바구니 주소는 기본이 **체험용 주소**(`address/orderExtras.ts` `DEMO_ADDRESS`, 우편번호 00000) — 데모에 실제 집 주소가 저장되지 않도록. '주소 검색'을 고르면 실제 주소 경고와 함께 우편번호 검색
- 배달 요청사항(선택, 100자, V19): 자주 쓰는 문구 칩 + 직접 입력(`components/DeliveryRequestField.tsx`), 고객 주문 상세·사장님 홈 티켓·주문 관리에 `요청 · …`로 표시

## 실시간 주문 알림 (#76)
- 사장님 계정이면 `OwnerProvider`가 `GET /api/owner/order-events`(SSE)에 연결 — 어느 화면에 있든 새 주문 토스트가 뜸. 홈·주문 화면 제목 옆에 연결 상태 배지와 '알림 소리 켜기'
- `EventSource` 대신 **fetch 스트림**(`api/orderStream.ts`): EventSource는 헤더를 못 붙여 토큰을 URL에 넣어야 함(접근 로그·기록에 남음). fetch는 Bearer·401 `code` 분기·single-flight 재발급을 다른 API와 공유
- 연결 관리는 `owner/orderStreamController.ts`(React와 분리해 가짜 타이머로 테스트, `orderStreamController.test.ts`)

| 상황 | 동작 |
|---|---|
| 연결됨 | `실시간 연결됨` 배지. `order` 이벤트 → 토스트 + 목록 다시 받기(0.4초 안에 여러 건이면 1번으로 모음) + 소리(켠 경우) |
| 서버가 10분 만료로 종료 | 실패로 세지 않고 바로 재연결 → 목록 다시 받기(끊긴 사이 놓친 주문) |
| 연결 실패 | 1·2·5·10·30초 간격(±20%)으로 재연결. **연속 3번 실패하면 30초마다 주문 조회**(`30초마다 확인 중` 배지), 뒤에서 재연결 계속 → 붙으면 조회 중단 |
| 60초 동안 아무것도 안 옴(하트비트 25초) | 응답이 멈춘 연결(절전·네트워크 전환)로 보고 끊은 뒤 재연결 |
| 401(재발급 실패)·403·404 | 다시 해도 같은 결과 → 멈춤(`실시간 알림 꺼짐`), 401은 로그인 화면으로 |

- 이벤트 본문은 토스트 문구에만 쓰고, 화면 데이터는 항상 주문 목록 API 결과
- **알림 소리**: 브라우저 자동 재생 정책상 사용자가 클릭하기 전에는 소리를 낼 수 없음 → '알림 소리 켜기' 클릭 안에서 `AudioContext`를 만들고 resume(확인용으로 한 번 울림, 막히면 오류 토스트). 음원 파일 없이 Web Audio로 두 음 생성. 새로고침하면 다시 켜야 함(저장해도 클릭 전에는 소리가 안 나므로 저장하지 않음)

## 토스페이먼츠 결제 (#78, 테스트 모드)
- 주문 상세: `GET /api/payments/toss/config`가 `enabled`면 '토스페이먼츠로 결제 (테스트)', 아니면(키 없음·조회 실패) 기존 모의 '카드 결제'
- 버튼 → `checkout`(금액·주문번호는 서버가 정함) → `payments/tossSdk.ts`가 SDK v2(`js.tosspayments.com/v2/standard`)를 **누를 때 처음 한 번만** 불러 결제창을 엶(리다이렉트 방식)
- 돌아오는 주소: `/payments/toss/success?order={주문 id}` / `/payments/toss/fail?order=` — 토스가 `orderId`(토스 주문번호)를 따로 붙여서 주문 id는 `order`
- 성공 화면(`pages/TossSuccessPage`): 서버 승인 요청, `Idempotency-Key` = 토스 주문번호 → 새로 고침해도 승인 한 번. 결과 불명(`PG_RESULT_UNKNOWN`·처리 중·네트워크)은 같은 요청을 1·2·4초 간격 자동 재시도(`payments/tossConfirm.ts`), 그래도 모르면 '다시 확인'만 — **결제창을 다시 여는 버튼은 두지 않음**(새 결제 = 이중 결제 위험)
- 클라이언트 키도 프론트 빌드 변수가 아니라 서버 API로 받음 → 키는 루트 `.env` 한 곳
- **가짜 결제창** (`pages/FakeTossCheckoutPage`, `/payments/fake-toss`): 서버가 `TOSS_MODE=fake`면 config의 `mode: 'FAKE'` → 토스 SDK 대신 이 화면으로 이동. 시나리오 버튼(정상·카드 거절·응답 유실·PG 지연·금액 조작·창 닫기)으로 결과를 고르고, 실제 결제창과 같은 successUrl·failUrl 쿼리로 돌아감
- 결제 상태 `PARTIALLY_REFUNDED`('부분 환불'), 주문 상세에 누적 환불액 표시 (V21). 관리자 부분 환불 화면은 아직 없음(API만)

## 소셜 로그인 (V22)
- 로그인·가입 화면 `components/SocialLoginButtons`(가입 화면은 `variant="signup"`으로 문구만 '시작하기', 동작은 같음 — 서버는 처음 로그인 = 고객 가입). 모양은 공급자 가이드 고정값(`base.css` `.btn--kakao` #FEE500·`.btn--google` 흰 바탕 + `components/SocialLogo` 인라인 SVG, 디자인 토큰 미사용), 카카오 먼저 표시: `GET /api/users/social/providers`로 받은 공급자만 버튼 표시(없으면 숨김), 클릭 시 서버 `/oauth2/authorization/{provider}`로 **페이지 전체 이동**
- 서버 콜백이 Refresh 쿠키만 심고 `/oauth/callback?result=login`으로 보냄 → 앱 시작 refresh(`AuthContext`)가 그대로 로그인을 복구 → `pages/OAuthCallbackPage`가 역할 첫 화면으로. 토큰은 URL에 없음
- `?error=CODE`는 `api/errorMessages.messageForCode`, 해석은 `api/social.parseOAuthCallback`(모르는 값은 일반 실패)
- 기존 계정 연결(내 정보 `SocialAccountsSection`): `POST .../link`를 **`credentials: 'include'`**로 호출해 연결 쿠키를 받은 뒤 `?mode=link`로 이동 → 돌아오면 `/me`에 안내. 관리자는 섹션을 숨김, 비밀번호 없는 회원의 마지막 계정은 해제 버튼 비활성
- 공급자 추가 시 프론트는 `api/social.ts`의 `SOCIAL_LABELS`에 이름만 추가

## 성능 기본값
| 항목 | 적용 |
|---|---|
| 코드 분할 | 화면별 `lazy` 청크(0.2~3 kB) + `vendor` 청크 분리 (라이브러리 캐시 유지) |
| 미리 받기 | 링크 hover·focus·touch 때 해당 화면 청크, 로그인 후 역할별 다음 화면을 idle 시간에 |
| 로딩 표시 | 0.4초 안에 끝나면 표시 안 함 |
| 폰트 | Plus Jakarta Sans(latin 400·600·700·800) + Gothic A1(400·700·800, unicode-range 분할)을 `@fontsource`로 **직접 호스팅**(외부 요청 0). 한글 @font-face가 많아 폰트 CSS(gzip 69 kB)는 첫 렌더 뒤 동적 import → 렌더링을 막는 CSS는 앱 CSS(gzip 6.8 kB)뿐, 폰트 전까지는 시스템 폰트(`font-display: swap`) |
| 숫자 | 금액·개수는 `.num`(`tabular-nums`) |
| 애니메이션 | transform·opacity만, `prefers-reduced-motion`이면 끔 |
| 반응형 | 디자인 기준 1100·960·640px — 960 이하 하단 탭바, 640 이하 주문 표 → 카드·정산 전표 → 바텀 시트. 넓은 표는 표 상자 안에서만 가로 스크롤 |
| 색·간격 | `tokens.css` 변수만 참조 (CSS에서 직접 색 지정 금지) |

빌드(2026-11-21, 디자인 적용 후): 초기 JS `index` 26.9 kB(gzip 9.2) + `vendor` 260.7 kB(gzip 82.9), 앱 CSS 31.5 kB(gzip 6.8), 폰트 CSS 188 kB(gzip 69.0, 지연 로드). 사장님 화면 청크 5~12 kB

## CI
`.github/workflows/frontend.yml` — `frontend/**`가 바뀐 main 대상 PR·push에서만 `npm ci` → `npm test` → `npm run build`. 백엔드(`ci.yml`)는 `src/**`·gradle 파일이 바뀔 때만

## 남은 할 일
- 화면 전환(View Transitions) 검토, 알림 배지 주기 조회 여부 결정 (새 주문은 #76 실시간 알림으로 처리)
- 실제 백엔드 데이터로 사장님 화면 수동 확인 — 로컬 더미 데이터 준비됨(2026-11-21, `fc110847` 매장 4·5, 상세는 루트 `CLAUDE.md`)

## 알려진 제약
- 실시간 알림은 서버 단일 인스턴스 기준(`docs/architecture.md` 실시간 주문 알림 장). Kafka가 멈추면 알림이 오지 않는데 연결은 살아 있어 `실시간 연결됨`으로 보임 — 수동 새로고침 필요
- 주문 목록 API가 id 오름차순만 지원 → 사장님 화면은 최신 주문을 보려고 keyset(100건씩)으로 **전체 주문을 끝까지** 받음(상한 5,000건, 넘으면 화면에 안내). 주문이 많아지면 백엔드에 최신순·기간(`from`) 필터 필요 — 오늘 집계·14일 매출도 서버 집계 API가 있으면 더 정확·가벼움
- 사장님 정산 화면은 선택 매장 + 최근 7·14·30일만 (이전 화면의 상태·임의 기간·전체 매장 조회는 디자인에서 빠짐, API는 그대로 지원)
- 메뉴 API에 매장 필터가 없어 매장 상세는 전체 메뉴를 페이지로 받아 거름 → 메뉴가 많아지면 `GET /api/menus?storeId=` 추가 필요 (백엔드)
- 매장 목록의 검색·정렬도 프론트 처리(최대 매장·메뉴 각 1,000건) → 커지면 서버 검색·정렬(`GET /api/stores?q=&sort=`)과 매장별 메뉴 요약(메뉴 수·최저가)을 응답에 포함 필요. 음식 카테고리 필터·대표 이미지·영업 상태는 매장 컬럼 추가(새 마이그레이션)가 먼저 필요
- 주소 검색 스크립트에는 쿼리 파라미터를 붙이지 않음 — 현재 카카오 스크립트는 파라미터(예전 가이드의 `?autoload=false` 포함)가 있으면 "허용되지 않는 파라미터" 오류를 띄움. 전역 이름은 `kakao.Postcode`(`daum`은 별칭)
- 주소 검색은 외부 스크립트(`t1.kakaocdn.net`)에 의존 — 못 불러오면(차단·오프라인·10초 초과) 우편번호·도로명 직접 입력으로 전환. 검색 창은 화면 안(embed)에 띄워 팝업 차단 영향 없음
- 사장님 메뉴 수정·삭제, 관리자 환불·수수료율·재집계 화면은 아직 없음 (API는 `endpoints.ts`에 일부만)
