import { useCallback, useEffect, useRef, useState, type MouseEvent } from 'react';
import { Link } from 'react-router-dom';
import { Logo } from '../components/Icons';
import { SERVICE_NAME } from '../components/Layout';
import { PrefetchLink } from '../components/PrefetchLink';
import { prefersReducedMotion } from './motion';

export const SECTIONS = [
  { id: 'intro', label: '서비스 소개' },
  { id: 'features', label: '주요 기능' },
  { id: 'how', label: '이용 안내' },
] as const;

/**
 * 같은 화면 안 섹션으로 이동 — 라우터 해시 대신 직접 스크롤해 주소가 바뀌지 않게 하고,
 * 키보드 사용자를 위해 포커스도 섹션으로 옮김 (섹션은 tabIndex=-1)
 */
export function goToSection(e: MouseEvent, id: string): void {
  const target = document.getElementById(id);
  if (!target) return;
  e.preventDefault();
  target.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' });
  target.focus({ preventScroll: true });
}

const MOBILE_QUERY = '(max-width: 860px)';

export function LandingHeader() {
  const [scrolled, setScrolled] = useState(false);
  const [open, setOpen] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  // 스크롤하면 배경·테두리가 생김 — 스크롤마다 setState하지 않도록 경계(8px)를 넘을 때만 바뀜
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const close = useCallback((returnFocus = false) => {
    setOpen(false);
    if (returnFocus) toggleRef.current?.focus();
  }, []);

  // 메뉴가 열린 동안: 뒤 화면 스크롤 막기, Esc로 닫기, 데스크톱 폭이 되면 닫기
  useEffect(() => {
    if (!open) return;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    // 메뉴 영역으로 포커스 이동 (첫 링크에 바로 두면 터치로 열었을 때도 포커스 테두리가 보임) → Tab으로 첫 링크
    panelRef.current?.querySelector<HTMLElement>('nav')?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close(true);
    const mq = window.matchMedia(MOBILE_QUERY);
    const onMq = () => !mq.matches && close();
    document.addEventListener('keydown', onKey);
    mq.addEventListener('change', onMq);
    return () => {
      document.body.style.overflow = prevOverflow;
      document.removeEventListener('keydown', onKey);
      mq.removeEventListener('change', onMq);
    };
  }, [open, close]);

  const navLinks = (onNavigate?: () => void) =>
    SECTIONS.map((s) => (
      <a
        key={s.id}
        href={`#${s.id}`}
        className="lhd__link"
        onClick={(e) => {
          onNavigate?.();
          goToSection(e, s.id);
        }}
      >
        {s.label}
      </a>
    ));

  return (
    <header className={`lhd${scrolled || open ? ' is-scrolled' : ''}${open ? ' is-open' : ''}`}>
      <div className="wrap lhd__in">
        <Link to="/" className="logo lhd__logo" aria-label={`${SERVICE_NAME} 홈`}>
          <Logo size={32} />
          <b>{SERVICE_NAME}</b>
        </Link>

        <nav className="lhd__nav" aria-label="주요 메뉴">
          {navLinks()}
        </nav>

        <div className="lhd__actions">
          <PrefetchLink to="/login" page="login" className="lhd__link lhd__login">
            로그인
          </PrefetchLink>
          <PrefetchLink to="/signup" page="signup" className="btn btn--sm lbtn">
            회원가입
          </PrefetchLink>
          <button
            ref={toggleRef}
            type="button"
            className="lhd__toggle"
            aria-expanded={open}
            aria-controls="landing-menu"
            aria-label={open ? '메뉴 닫기' : '메뉴 열기'}
            onClick={() => setOpen((v) => !v)}
          >
            <span aria-hidden="true" />
          </button>
        </div>
      </div>

      {/* 모바일 메뉴 — 닫혀 있을 때는 inert로 탭 이동·스크린리더에서 제외 */}
      <div id="landing-menu" ref={panelRef} className="lhd__panel" inert={!open}>
        <nav className="wrap lhd__panel-in" aria-label="모바일 메뉴" tabIndex={-1}>
          {navLinks(() => close())}
          <PrefetchLink to="/stores" page="stores" className="lhd__link" onClick={() => close()}>
            매장 둘러보기
          </PrefetchLink>
          <div className="lhd__panel-cta">
            <PrefetchLink to="/login" page="login" className="btn btn--lg btn--ghost lbtn" onClick={() => close()}>
              로그인
            </PrefetchLink>
            <PrefetchLink to="/signup" page="signup" className="btn btn--lg lbtn" onClick={() => close()}>
              회원가입
            </PrefetchLink>
          </div>
        </nav>
      </div>
    </header>
  );
}
