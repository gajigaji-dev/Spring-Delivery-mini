import { Suspense, useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { notificationApi } from '../api/endpoints';
import type { Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import { preloadWhenIdle, type PageName } from '../routes/pages';
import { DelayedSpinner } from './DelayedSpinner';
import { PrefetchLink } from './PrefetchLink';

interface NavItem {
  to: string;
  label: string;
  page: PageName;
  roles?: Role[];
}

const NAV: NavItem[] = [
  { to: '/', label: '매장', page: 'stores' },
  { to: '/cart', label: '장바구니', page: 'cart', roles: ['CUSTOMER'] },
  { to: '/orders', label: '주문', page: 'orders', roles: ['CUSTOMER', 'OWNER'] },
  { to: '/payments', label: '결제 내역', page: 'payments', roles: ['CUSTOMER'] },
  { to: '/owner/stores', label: '내 매장', page: 'ownerStores', roles: ['OWNER'] },
  { to: '/owner/settlements', label: '정산', page: 'ownerSettlements', roles: ['OWNER'] },
  { to: '/admin/settlements', label: '정산 관리', page: 'adminSettlements', roles: ['ADMIN'] },
  { to: '/admin/outbox', label: '이벤트', page: 'adminOutbox', roles: ['ADMIN'] },
];

// 역할별로 다음에 갈 가능성이 높은 화면
const IDLE_PRELOAD: Record<Role, PageName[]> = {
  CUSTOMER: ['storeDetail', 'cart', 'orders', 'orderDetail'],
  OWNER: ['orders', 'orderDetail', 'ownerStores', 'ownerSettlements'],
  ADMIN: ['adminSettlements', 'adminOutbox'],
};

export function Layout() {
  const { status, me, logout } = useAuth();
  const { lines } = useCart();
  const navigate = useNavigate();
  const location = useLocation();
  const [unread, setUnread] = useState<number | null>(null);

  useEffect(() => {
    if (!me) return;
    return preloadWhenIdle(IDLE_PRELOAD[me.role]);
  }, [me]);

  useEffect(() => {
    // 배지는 화면 이동 때만 갱신 — 주기 조회는 디자인·요구가 정해지면 (백그라운드 탭에서는 멈추게) 추가
    if (status !== 'authenticated') {
      setUnread(null);
      return;
    }
    notificationApi
      .unreadCount()
      .then((r) => setUnread(r.unreadCount))
      .catch(() => setUnread(null));
  }, [status, location.pathname]);

  const visibleNav = NAV.filter((n) => !n.roles || (me && n.roles.includes(me.role)));
  const cartCount = lines.reduce((s, l) => s + l.quantity, 0);

  return (
    <div className="app">
      <header className="header">
        <div className="header__inner">
          <PrefetchLink to="/" page="stores" className="brand">
            Delivery mini
          </PrefetchLink>
          <nav className="nav" aria-label="주요 메뉴">
            {visibleNav.map((n) => (
              <PrefetchLink key={n.to} to={n.to} page={n.page} end={n.to === '/'} className="nav__link">
                {n.label}
                {n.page === 'cart' && cartCount > 0 && <span className="count num">{cartCount}</span>}
              </PrefetchLink>
            ))}
          </nav>
          <div className="account">
            {status === 'authenticated' && me ? (
              <>
                <PrefetchLink to="/notifications" page="notifications" className="nav__link">
                  알림{unread ? <span className="count num">{unread}</span> : null}
                </PrefetchLink>
                <PrefetchLink to="/me" page="me" className="nav__link">
                  {me.username}
                </PrefetchLink>
                <button
                  type="button"
                  className="btn btn--ghost"
                  onClick={async () => {
                    await logout();
                    navigate('/login');
                  }}
                >
                  로그아웃
                </button>
              </>
            ) : status === 'anonymous' ? (
              <>
                <PrefetchLink to="/login" page="login" className="nav__link">
                  로그인
                </PrefetchLink>
                <PrefetchLink to="/signup" page="signup" className="nav__link">
                  회원가입
                </PrefetchLink>
              </>
            ) : null}
          </div>
        </div>
      </header>
      <main className="main">
        {/* key로 경로마다 새 Suspense → 화면 진입 애니메이션이 매번 실행됨 */}
        <Suspense key={location.pathname} fallback={<DelayedSpinner />}>
          <div className="page">
            <Outlet />
          </div>
        </Suspense>
      </main>
    </div>
  );
}
