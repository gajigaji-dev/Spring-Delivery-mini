import { ApiError, get, getPage, request } from './client';
import type {
  AdminSettlement,
  Menu,
  MyInfo,
  Notification,
  Order,
  OrderDetail,
  OrderStatus,
  OutboxEvent,
  OutboxStatus,
  OwnerApplication,
  OwnerApplicationStatus,
  Payment,
  SettlementStatus,
  Settlement,
  Store,
  TokenResponse,
} from './types';

// 화면에서 쓰는 API 모음 — URL·파라미터 기준은 docs/api.md "API 목록"
export const authApi = {
  // 가입은 항상 고객 — 사장님은 가입 후 전환 신청 + 관리자 승인 (V17)
  signup: (username: string, password: string) =>
    request('/api/users/signup', { method: 'POST', body: { username, password }, auth: false }),
  login: async (username: string, password: string) =>
    (
      await request<TokenResponse>('/api/users/login', {
        method: 'POST',
        body: { username, password },
        auth: false,
        withCredentials: true,
      })
    ).data,
  logout: () => request('/api/users/logout', { method: 'POST', auth: false, withCredentials: true }),
  me: () => get<MyInfo>('/api/users/me'),
};

export const storeApi = {
  list: (page: number, size = 20) => getPage<Store>('/api/stores', { page, size }, false),
  get: (id: number) => get<Store>(`/api/stores/${id}`, undefined, false),
  create: async (name: string) => (await request<Store>('/api/stores', { method: 'POST', body: { name } })).data,
  orders: (id: number, page: number, size = 20) => getPage<Order>(`/api/stores/${id}/orders`, { page, size }),
};

export const menuApi = {
  // 서버에 매장 필터가 없음 → 매장 화면은 받은 페이지에서 storeId로 거른다 (docs/api.md 메뉴 목록)
  list: (page: number, size = 100) => getPage<Menu>('/api/menus', { page, size }, false),
  create: async (body: { storeId: number; name: string; price: number; description?: string }) =>
    (await request<Menu>('/api/menus', { method: 'POST', body })).data,
  update: async (id: number, body: { name: string; price: number; description?: string }) =>
    (await request<Menu>(`/api/menus/${id}`, { method: 'PUT', body })).data,
  remove: (id: number) => request(`/api/menus/${id}`, { method: 'DELETE' }),
};

export const orderApi = {
  // keyset: 첫 페이지는 afterId 없이, 다음은 X-Next-After-Id 값 (뒤 페이지도 응답 시간 일정)
  list: (afterId: number | null, size = 20) => getPage<Order>('/api/orders', { afterId: afterId ?? undefined, size }),
  get: (id: number) => get<OrderDetail>(`/api/orders/${id}`),
  create: async (items: { menuId: number; quantity: number }[], deliveryAddress: string) =>
    (await request<Order>('/api/orders', { method: 'POST', body: { items, deliveryAddress } })).data,
  cancel: (id: number) => request(`/api/orders/${id}/cancel`, { method: 'PATCH' }),
  changeStatus: (id: number, status: Extract<OrderStatus, 'ORDER_ACCEPTED' | 'DELIVERY_COMPLETED'>) =>
    request(`/api/orders/${id}/status`, { method: 'PATCH', body: { status } }),
};

export const paymentApi = {
  pay: async (orderId: number, idempotencyKey: string) =>
    (
      await request<Payment>('/api/payments', {
        method: 'POST',
        body: { orderId, paymentMethod: 'CARD' },
        headers: { 'Idempotency-Key': idempotencyKey },
      })
    ).data,
  list: (page: number, size = 20) => getPage<Payment>('/api/payments', { page, size }),
  get: (id: number) => get<Payment>(`/api/payments/${id}`),
};

export const notificationApi = {
  list: (page: number, size = 20) => getPage<Notification>('/api/notifications', { page, size }),
  unreadCount: () => get<{ unreadCount: number }>('/api/notifications/unread-count'),
  markRead: (id: number) => request(`/api/notifications/${id}/read`, { method: 'PATCH' }),
};

export const settlementApi = {
  mine: (q: { storeId?: number; status?: SettlementStatus; from?: string; to?: string; page: number; size?: number }) =>
    getPage<Settlement>('/api/settlements', { ...q, size: q.size ?? 20 }),
};

export const ownerApplicationApi = {
  apply: async (businessName: string) =>
    (await request<OwnerApplication>('/api/owner-applications', { method: 'POST', body: { businessName } })).data,
  /** 신청 내역이 없으면 null (서버 404) */
  mine: async (): Promise<OwnerApplication | null> => {
    try {
      return await get<OwnerApplication>('/api/owner-applications/me');
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return null;
      throw e;
    }
  },
};

export const adminApi = {
  ownerApplications: (status: OwnerApplicationStatus, page: number, size = 20) =>
    getPage<OwnerApplication>('/api/admin/owner-applications', { status, page, size }),
  approveOwner: async (id: number) =>
    (await request<OwnerApplication>(`/api/admin/owner-applications/${id}/approve`, { method: 'PATCH' })).data,
  rejectOwner: async (id: number, reason: string) =>
    (await request<OwnerApplication>(`/api/admin/owner-applications/${id}/reject`, { method: 'PATCH', body: { reason } }))
      .data,
  settlements: (date: string, status?: SettlementStatus) =>
    get<AdminSettlement[]>('/api/admin/settlements', { date, status }),
  confirm: async (id: number) =>
    (await request<AdminSettlement>(`/api/admin/settlements/${id}/confirm`, { method: 'PATCH' })).data,
  pay: async (id: number) =>
    (await request<AdminSettlement>(`/api/admin/settlements/${id}/pay`, { method: 'PATCH' })).data,
  outboxEvents: (status: OutboxStatus, page: number, size = 20) =>
    getPage<OutboxEvent>('/api/admin/outbox-events', { status, page, size }),
  republish: async (id: number) =>
    (await request<OutboxEvent>(`/api/admin/outbox-events/${id}/republish`, { method: 'POST' })).data,
};
