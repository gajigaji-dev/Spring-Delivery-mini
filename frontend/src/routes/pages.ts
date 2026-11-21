import { lazy, type ComponentType } from 'react';

type PageModule = Promise<{ default: ComponentType }>;

// 화면별 코드 분할: 각 화면은 처음 들어갈 때 청크를 받는다.
// 같은 import()를 미리 호출하면(preloadPage) 모듈 캐시에 올라가 실제 이동 시 대기가 없음
const loaders = {
  landing: () => import('../pages/LandingPage'),
  stores: () => import('../pages/StoresPage'),
  storeDetail: () => import('../pages/StoreDetailPage'),
  login: () => import('../pages/LoginPage'),
  signup: () => import('../pages/SignupPage'),
  cart: () => import('../pages/CartPage'),
  orders: () => import('../pages/OrdersPage'),
  orderDetail: () => import('../pages/OrderDetailPage'),
  payments: () => import('../pages/PaymentsPage'),
  tossSuccess: () => import('../pages/TossSuccessPage'),
  tossFail: () => import('../pages/TossFailPage'),
  fakeTossCheckout: () => import('../pages/FakeTossCheckoutPage'),
  notifications: () => import('../pages/NotificationsPage'),
  me: () => import('../pages/MePage'),
  ownerHome: () => import('../pages/owner/OwnerHomePage'),
  ownerOrders: () => import('../pages/owner/OwnerOrdersPage'),
  ownerStores: () => import('../pages/owner/OwnerStoresPage'),
  ownerSettlements: () => import('../pages/owner/OwnerSettlementsPage'),
  adminSettlements: () => import('../pages/admin/AdminSettlementsPage'),
  adminOutbox: () => import('../pages/admin/AdminOutboxPage'),
  adminOwnerApplications: () => import('../pages/admin/AdminOwnerApplicationsPage'),
  notFound: () => import('../pages/NotFoundPage'),
} satisfies Record<string, () => PageModule>;

export type PageName = keyof typeof loaders;

export const pages = Object.fromEntries(
  Object.entries(loaders).map(([name, load]) => [name, lazy(load)]),
) as unknown as Record<PageName, ReturnType<typeof lazy>>;

export function preloadPage(name: PageName): void {
  // 실패해도 실제 이동 때 다시 받으므로 무시
  loaders[name]().catch(() => undefined);
}

/** 브라우저가 한가할 때 다음에 갈 가능성이 높은 화면을 미리 받아 둠 */
export function preloadWhenIdle(names: PageName[]): () => void {
  const run = () => names.forEach(preloadPage);
  if ('requestIdleCallback' in window) {
    const id = window.requestIdleCallback(run, { timeout: 3000 });
    return () => window.cancelIdleCallback(id);
  }
  const t = setTimeout(run, 1500);
  return () => clearTimeout(t);
}
