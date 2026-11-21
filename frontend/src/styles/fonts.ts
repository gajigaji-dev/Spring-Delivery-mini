// 웹폰트는 사용 굵기만 직접 호스팅(빌드 산출물에 포함, 외부 요청 없음) — main.tsx에서 첫 렌더 뒤 동적 import
// - Plus Jakarta Sans(숫자·라틴): latin 부분집합만
// - Gothic A1(한글): unicode-range로 쪼갠 파일이라 화면에 쓴 글자 묶음만 받음. 굵기마다 @font-face가 약 100개라
//   CSS가 커서 600은 빼고 400·700·800만 — 600 요청은 브라우저가 가까운 700으로 그림
import '@fontsource/plus-jakarta-sans/latin-400.css';
import '@fontsource/plus-jakarta-sans/latin-600.css';
import '@fontsource/plus-jakarta-sans/latin-700.css';
import '@fontsource/plus-jakarta-sans/latin-800.css';
import '@fontsource/gothic-a1/400.css';
import '@fontsource/gothic-a1/700.css';
import '@fontsource/gothic-a1/800.css';
