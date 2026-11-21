# Frontend (#70) — 골격

React 19 + TypeScript + Vite 7 + React Router 7. **디자인 확정 전 골격**: 화면 흐름·API 연동·성능 기본값만 갖추고, 시각 디자인은 `src/styles/tokens.css` 임시 값.

## 실행
```bash
cp .env.example .env      # VITE_API_BASE_URL (기본 http://localhost:8090, compose 앱이면 8091)
npm install
npm run dev               # http://localhost:5173 고정 — 백엔드 CORS 기본 허용 출처
npm test                  # vitest (API 클라이언트 인증 처리)
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
| `src/components/` | `Layout`, `PrefetchLink`, `DelayedSpinner`(0.4초 지연), `Money`(tabular-nums), `ErrorMessage`(traceId 표시), `Pager` |
| `src/pages/` | 고객: 매장·매장 상세·장바구니·주문(keyset)·주문 상세(결제·취소)·결제 내역 / 사장님: 내 매장·메뉴 등록·주문 상태 변경·정산 내역 / 관리자: 정산 확정·지급, Outbox 재발행 / 공통: 로그인·가입·알림·내 정보 |

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
| 폰트 | 시스템 폰트(외부 요청 0). 웹폰트 도입 시 **사용 굵기만 직접 호스팅 + preload**, 외부 CSS·100~900 전체 요청 금지 |
| 숫자 | 금액·개수는 `.num`(`tabular-nums`) |
| 애니메이션 | transform·opacity만, `prefers-reduced-motion`이면 끔 |
| 반응형 | 768px·1024px 두 구간만, 넓은 표는 표 안에서만 가로 스크롤 |
| 색·간격 | `tokens.css` 변수만 참조 (직접 색 지정 금지) → 디자인 확정 시 이 파일 교체 |

초기 빌드(2026-11-21): 초기 JS `index` 14.4 kB(gzip 5.4) + `vendor` 260.7 kB(gzip 82.9), CSS 5.0 kB(gzip 1.6).

## 디자인 확정 후 할 일
- `tokens.css` 값 교체, 웹폰트(사용 굵기만) 추가, 화면 전환(View Transitions) 검토
- 알림 배지 주기 조회(백그라운드 탭에서는 중지) 여부 결정

## 알려진 제약
- 메뉴 API에 매장 필터가 없어 매장 상세는 전체 메뉴를 페이지로 받아 거름 → 메뉴가 많아지면 `GET /api/menus?storeId=` 추가 필요 (백엔드)
- 사장님 메뉴 수정·삭제, 관리자 환불·수수료율·재집계 화면은 아직 없음 (API는 `endpoints.ts`에 일부만)
