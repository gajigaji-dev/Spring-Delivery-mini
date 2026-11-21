import { Suspense, useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { notificationApi } from '../api/endpoints';
import type { Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import { useOwner } from '../owner/OwnerContext';
import { preloadWhenIdle, type PageName } from '../routes/pages';
import { DelayedSpinner } from './DelayedSpinner';
import {
  IconBag,
  IconBell,
  IconCart,
  IconCheck,
  IconChevronDown,
  IconHome,
  IconLogout,
  IconPulse,
  IconReceipt,
  IconStore,
  IconUser,
  IconUserCheck,
  IconWallet,
  Logo,
} from './Icons';
import { PrefetchLink } from './PrefetchLink';
import { useToast } from './Toast';

export const SERVICE_NAME = 'Delivery mini';

interface NavItem {
  to: string;
  label: string;
  page: PageName;
  icon: ReactNode;
  badge?: 'cart' | 'wait';
}

// 역할별 주 메뉴 — 데스크톱은 알약형 메뉴, 960px 이하는 같은 항목이 하단 탭바로
const NAV: Record<Role | 'ANONYMOUS', NavItem[]> = {
  ANONYMOUS: [{ to: '/', label: '매장', page: 'stores', icon: <IconStore size={24} /> }],
  CUSTOMER: [
    { to: '/', label: '매장', page: 'stores', icon: <IconStore size={24} /> },
    { to: '/cart', label: '장바구니', page: 'cart', icon: <IconCart size={24} />, badge: 'cart' },
    { to: '/orders', label: '주문', page: 'orders', icon: <IconBag size={24} /> },
    { to: '/payments', label: '결제 내역', page: 'payments', icon: <IconWallet size={24} /> },
  ],
  OWNER: [
    { to: '/owner', label: '홈', page: 'ownerHome', icon: <IconHome size={24} /> },
    { to: '/owner/orders', label: '주문', page: 'ownerOrders', icon: <IconBag size={24} />, badge: 'wait' },
    { to: '/owner/settlements', label: '정산', page: 'ownerSettlements', icon: <IconWallet size={24} /> },
  ],
  ADMIN: [
    { to: '/admin/settlements', label: '정산 관리', page: 'adminSettlements', icon: <IconReceipt size={24} /> },
    { to: '/admin/owner-applications', label: '사장님 승인', page: 'adminOwnerApplications', icon: <IconUserCheck size={24} /> },
    { to: '/admin/outbox', label: '이벤트', page: 'adminOutbox', icon: <IconPulse size={24} /> },
  ],
};

export const ROLE_HOME: Record<Role, string> = {
  CUSTOMER: '/',
  OWNER: '/owner',
  ADMIN: '/admin/settlements',
};

const ROLE_TAG: Record<Role, string | null> = { CUSTOMER: null, OWNER: '사장님', ADMIN: '관리자' };
const ROLE_INITIAL: Record<Role, string> = { CUSTOMER: '고', OWNER: '사', ADMIN: '관' };

// 역할별로 다음에 갈 가능성이 높은 화면
const IDLE_PRELOAD: Record<Role, PageName[]> = {
  CUSTOMER: ['storeDetail', 'cart', 'orders', 'orderDetail'],
  OWNER: ['ownerHome', 'ownerOrders', 'ownerSettlements', 'orderDetail'],
  ADMIN: ['adminSettlements', 'adminOwnerApplications', 'adminOutbox'],
};

// 로그인·가입은 자체 화면(제품 소개 + 패널)이라 앱 헤더를 쓰지 않음
const BARE_PATHS = ['/login', '/signup'];

/** 바깥 클릭·Esc로 닫히는 팝오버 상태 */
function usePopover() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);
  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Escape') setOpen(false);
  };
  return { open, setOpen, ref, onKeyDown };
}

export function Layout() {
  const { status, me } = useAuth();
  const location = useLocation();

  useEffect(() => {
    if (!me) return;
    return preloadWhenIdle(IDLE_PRELOAD[me.role]);
  }, [me]);

  if (BARE_PATHS.includes(location.pathname)) {
    return (
      <Suspense key={location.pathname} fallback={<DelayedSpinner />}>
        <Outlet />
      </Suspense>
    );
  }

  const role: Role | 'ANONYMOUS' = status === 'authenticated' && me ? me.role : 'ANONYMOUS';
  const nav = NAV[role];
  const hasTabbar = nav.length > 1;

  return (
    <div className={`app${hasTabbar ? ' app--tabbar' : ''}`}>
      <Header nav={nav} />
      <main className="wrap main">
        {/* key로 경로마다 새 Suspense → 화면 진입 애니메이션이 매번 실행됨 */}
        <Suspense key={location.pathname} fallback={<DelayedSpinner />}>
          <Outlet />
        </Suspense>
      </main>
      {hasTabbar && <TabBar nav={nav} />}
      <footer className="wrap foot hide-sm">
        <span>{SERVICE_NAME}{me?.role === 'OWNER' ? ' 점주 콘솔' : ''}</span>
        <span>문의 시 화면의 문의 코드를 함께 전달해 주세요</span>
      </footer>
    </div>
  );
}

function useBadges() {
  const { me } = useAuth();
  const { lines } = useCart();
  const { waitCount } = useOwner();
  return {
    cart: me?.role === 'CUSTOMER' ? lines.reduce((s, l) => s + l.quantity, 0) : 0,
    wait: me?.role === 'OWNER' ? waitCount : 0,
  };
}

function Header({ nav }: { nav: NavItem[] }) {
  const { status, me } = useAuth();
  const badges = useBadges();
  const home = me ? ROLE_HOME[me.role] : '/';

  return (
    <header className="top">
      <div className="wrap top-in">
        <div className="top-left">
          <Link to={home} className="logo" aria-label="홈으로">
            <Logo />
            <b className="hide-sm">{SERVICE_NAME}</b>
            {me && ROLE_TAG[me.role] && <span className="tag-role">{ROLE_TAG[me.role]}</span>}
          </Link>
          {nav.length > 1 && (
            <nav className="pnav" aria-label="주 메뉴">
              {nav.map((n) => (
                <PrefetchLink key={n.to} to={n.to} page={n.page} end={n.to === '/' || n.to === '/owner'}>
                  {n.label}
                  {n.badge && badges[n.badge] > 0 && <span className="badge-count num">{badges[n.badge]}</span>}
                </PrefetchLink>
              ))}
            </nav>
          )}
        </div>
        <div className="top-right">
          {status === 'authenticated' && me ? (
            <>
              {me.role === 'OWNER' && <StoreSwitcher />}
              <AccountMenu />
            </>
          ) : status === 'anonymous' ? (
            <>
              <PrefetchLink to="/login" page="login" className="link">
                로그인
              </PrefetchLink>
              <PrefetchLink to="/signup" page="signup" className="btn btn--ink">
                회원가입
              </PrefetchLink>
            </>
          ) : null}
        </div>
      </div>
    </header>
  );
}

function StoreSwitcher() {
  const { stores, store, selectStore } = useOwner();
  const { open, setOpen, ref, onKeyDown } = usePopover();
  if (!store) return null;

  return (
    <div className="popover" ref={ref} onKeyDown={onKeyDown}>
      <button type="button" className="sbtn" onClick={() => setOpen(!open)} aria-haspopup="menu" aria-expanded={open}>
        <span className="sav">{store.name.slice(0, 1)}</span>
        <span className="sname">{store.name}</span>
        <IconChevronDown />
      </button>
      {open && (
        <div className="menu" role="menu">
          <div className="menu__head">매장 전환</div>
          {stores.map((s) => (
            <button
              key={s.id}
              type="button"
              role="menuitem"
              className="menu__item"
              onClick={() => {
                setOpen(false);
                if (s.id !== store.id) selectStore(s.id);
              }}
            >
              <span className="menu__label">
                <span className="sav sav--sm">{s.name.slice(0, 1)}</span>
                {s.name}
              </span>
              {s.id === store.id && (
                <span className="fg-wait">
                  <IconCheck />
                </span>
              )}
            </button>
          ))}
          <div className="menu__sep" />
          <PrefetchLink to="/owner/stores" page="ownerStores" role="menuitem" className="menu__item" onClick={() => setOpen(false)}>
            <span>매장 정보 · 메뉴 관리</span>
            <IconStore />
          </PrefetchLink>
        </div>
      )}
    </div>
  );
}

function AccountMenu() {
  const { status, me, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { notify } = useToast();
  const { open, setOpen, ref, onKeyDown } = usePopover();
  const [unread, setUnread] = useState(0);

  useEffect(() => {
    // 배지는 화면 이동 때만 갱신 — 주기 조회는 요구가 정해지면 (백그라운드 탭에서는 멈추게) 추가
    if (status !== 'authenticated') return;
    notificationApi
      .unreadCount()
      .then((r) => setUnread(r.unreadCount))
      .catch(() => setUnread(0));
  }, [status, location.pathname]);

  if (!me) return null;

  return (
    <div className="popover" ref={ref} onKeyDown={onKeyDown}>
      <button
        type="button"
        className="abtn"
        onClick={() => setOpen(!open)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={unread ? `계정 메뉴, 읽지 않은 알림 ${unread}개` : '계정 메뉴'}
      >
        {ROLE_INITIAL[me.role]}
        {unread > 0 && <span className="abtn__dot" aria-hidden="true" />}
      </button>
      {open && (
        <div className="menu" role="menu">
          <div className="menu__head">
            {me.username} · {me.role}
          </div>
          <PrefetchLink to="/me" page="me" role="menuitem" className="menu__item" onClick={() => setOpen(false)}>
            <span>내 정보</span>
            <IconUser />
          </PrefetchLink>
          <PrefetchLink to="/notifications" page="notifications" role="menuitem" className="menu__item" onClick={() => setOpen(false)}>
            <span>알림</span>
            {unread > 0 ? <span className="badge-count num">{unread}</span> : <IconBell />}
          </PrefetchLink>
          <div className="menu__sep" />
          <button
            type="button"
            role="menuitem"
            className="menu__item"
            onClick={async () => {
              setOpen(false);
              await logout();
              notify('로그아웃되었습니다');
              navigate('/login');
            }}
          >
            <span>로그아웃</span>
            <IconLogout />
          </button>
        </div>
      )}
    </div>
  );
}

function TabBar({ nav }: { nav: NavItem[] }) {
  const badges = useBadges();
  return (
    <nav className="tabbar" aria-label="하단 메뉴">
      {nav.map((n) => (
        <PrefetchLink key={n.to} to={n.to} page={n.page} end={n.to === '/' || n.to === '/owner'}>
          {n.icon}
          {n.label}
          {n.badge && badges[n.badge] > 0 && <span className="badge-count num">{badges[n.badge]}</span>}
        </PrefetchLink>
      ))}
    </nav>
  );
}
