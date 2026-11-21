# Frontend (#70)

React 19 + TypeScript + Vite 7 + React Router 7. 디자인은 **점주 콘솔 V3**(2026-11-21, claude.ai 디자인 캔버스 — 로그인·홈·주문·정산 + 디자인 시스템) 기준.

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
| `src/auth/` | Access Token 메모리 보관(`tokenStore`), 앱 시작 시 refresh 1번, 역할별 화면 가드(`RequireRole`, 최종 판단은 서버 403) |
| `src/cart/` | 장바구니 — 한 매장 메뉴만, 같은 메뉴는 합침 (서버 400 규칙과 동일) |
| `src/routes/pages.ts` | 화면별 지연 로딩 + 미리 받기(`preloadPage`, `preloadWhenIdle`) |
| `src/components/` | `Layout`(헤더 알약 메뉴·매장 전환·계정 메뉴, 960px 이하 하단 탭바), `AuthShell`(로그인·가입 화면), `BootScreen`, `Toast`, `Icons`(인라인 SVG), `PrefetchLink`, `DelayedSpinner`(0.4초 지연), `Money`·`won`(₩, tabular-nums), `ErrorMessage`(문의 코드 = traceId), `Pager` |
| `src/owner/` | `OwnerContext`(내 매장·선택 매장·주문 목록·수락/배달 완료), `useStoreSettlements`(선택 매장 최근 n일 정산), `derive.ts`(오늘 집계·14일 매출·주간 묶음·CSV — 순수 함수, 테스트 대상) |
| `src/pages/` | 고객: 매장·매장 상세·장바구니·주문(keyset)·주문 상세(결제·취소)·결제 내역·사장님 전환 신청(내 정보) / 사장님: 홈(`/owner`)·주문 관리(`/owner/orders`)·정산(`/owner/settlements`)·매장 정보·메뉴 등록(`/owner/stores`) / 관리자: 정산 확정·지급, 사장님 전환 승인·거절, Outbox 재발행 / 공통: 로그인·가입(항상 고객)·알림·내 정보 |
| `src/styles/` | `tokens.css`(디자인 토큰), `base.css`(공통 컴포넌트 + 고객·관리자 화면이 쓰는 기존 클래스), `owner.css`(사장님 화면·로그인), `fonts.ts`(웹폰트, 첫 렌더 뒤 로드) |

### 디자인 적용 (점주 콘솔 V3)
- 토큰: 오트 배경 `#F6F4EF` → 흰 면 → 연한 상태 면, 토마토(`#FF5B37`·버튼 `#CF3E1E`)는 "지금 해야 할 일"에만, 잉크 다크 패널(돈)은 화면당 하나. 라이트 전용(다크 모드 없음)
- 상태 칩: 수락 대기(`PAYMENT_COMPLETED`)·배달 중(`ORDER_ACCEPTED`)·배달 완료·취소·결제 대기(`ORDER_REQUEST`, 사장님은 처리 불가) / 정산 예정(`PENDING`)·입금 대기(`CONFIRMED`)·지급 완료(`PAID`)
- 로그인 후 역할별 첫 화면: 고객 `/`, 사장님 `/owner`, 관리자 `/admin/settlements` (막혔던 화면이 있으면 그리로)
- 고객·관리자 화면은 별도 디자인이 없어 구조는 그대로 두고 공통 클래스(`btn`·`card`·`badge`·표)를 디자인 시스템 모양으로 바꿈
- 디자인과 다른 점(백엔드 기준): **지급 실패** 상태 없음(정산 상태는 3개) → 정산 예정 카드는 확정 전·입금 대기 2칸. 지급일 "D+2" 대신 실제 `paidAt`. 수수료율은 매장 요율 API가 없어 기간 합계(수수료 ÷ 매출)로 표시. 환불 차감·이월이 있으면 계산 블록 아래·전표에 별도 표시. 테스트 계정 버튼은 `VITE_DEMO_ACCOUNTS`가 있을 때만(계정을 소스에 넣지 않음). 서비스명은 `Delivery mini`
- 사장님 홈 계산: 오늘 매출 = 오늘 생성된 주문 중 결제 완료·배달 중·배달 완료 합계(결제 전·취소 제외), 14일 매출도 같은 기준(주문 생성일). 정산 예정액 = 최근 14일 `PENDING` 정산액 합

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
- 화면 전환(View Transitions) 검토, 알림 배지·대기 주문 주기 조회(백그라운드 탭에서는 중지) 여부 결정
- 실제 백엔드 데이터로 사장님 화면 수동 확인 — 로컬 더미 데이터 준비됨(2026-11-21, `fc110847` 매장 4·5, 상세는 루트 `CLAUDE.md`)

## 알려진 제약
- 주문 목록 API가 id 오름차순만 지원 → 사장님 화면은 최신 주문을 보려고 keyset(100건씩)으로 **전체 주문을 끝까지** 받음(상한 5,000건, 넘으면 화면에 안내). 주문이 많아지면 백엔드에 최신순·기간(`from`) 필터 필요 — 오늘 집계·14일 매출도 서버 집계 API가 있으면 더 정확·가벼움
- 사장님 정산 화면은 선택 매장 + 최근 7·14·30일만 (이전 화면의 상태·임의 기간·전체 매장 조회는 디자인에서 빠짐, API는 그대로 지원)
- 메뉴 API에 매장 필터가 없어 매장 상세는 전체 메뉴를 페이지로 받아 거름 → 메뉴가 많아지면 `GET /api/menus?storeId=` 추가 필요 (백엔드)
- 사장님 메뉴 수정·삭제, 관리자 환불·수수료율·재집계 화면은 아직 없음 (API는 `endpoints.ts`에 일부만)
