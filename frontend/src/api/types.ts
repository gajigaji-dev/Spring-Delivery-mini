// 백엔드 응답 형식 — 기준은 docs/api.md. 날짜·시각은 Asia/Seoul 기준 문자열(ISO, 오프셋 없음)
export type Role = 'CUSTOMER' | 'OWNER' | 'ADMIN';

export type OrderStatus =
  | 'ORDER_REQUEST'
  | 'PAYMENT_COMPLETED'
  | 'ORDER_ACCEPTED'
  | 'DELIVERY_COMPLETED'
  | 'ORDER_CANCELLED';

export type PaymentStatus = 'COMPLETED' | 'CANCELLED' | 'REFUNDED';
export type SettlementStatus = 'PENDING' | 'CONFIRMED' | 'PAID';
export type OutboxStatus = 'PENDING' | 'PUBLISHED' | 'FAILED';

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

export interface MyInfo {
  id: number;
  username: string;
  role: Role;
  createdAt: string;
  storeIds: number[];
}

export interface Store {
  id: number;
  name: string;
  ownerId: number;
  createdAt: string;
  updatedAt: string;
}

export interface Menu {
  id: number;
  storeId: number;
  name: string;
  price: number;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OrderItem {
  menuId: number;
  menuName: string;
  unitPrice: number;
  quantity: number;
  linePrice: number;
}

export interface Order {
  id: number;
  storeId: number;
  storeName: string;
  items: OrderItem[];
  totalPrice: number;
  /** 한 줄 주소 (도로명 + 상세) — 기존 주문도 항상 있음. 화면 표시는 이 값 */
  deliveryAddress: string;
  /** 나눈 주소 (#77) — V18 이전 주문은 null */
  zipCode: string | null;
  roadAddress: string | null;
  detailAddress: string | null;
  /** 배달 요청사항 (선택, V19) — 없으면 null */
  deliveryRequest: string | null;
  status: OrderStatus;
  createdAt: string;
  updatedAt: string;
  deliveredAt: string | null;
}

export interface OrderPayment {
  id: number;
  amount: number;
  paymentMethod: 'CARD';
  status: PaymentStatus;
  paidAt: string;
  cancelledAt: string | null;
  refundedAt: string | null;
}

export interface OrderDetail extends Order {
  payment: OrderPayment | null;
}

export interface Payment {
  id: number;
  orderId: number;
  amount: number;
  paymentMethod: 'CARD';
  orderStatus: OrderStatus;
  paidAt: string;
  status: PaymentStatus;
  cancelledAt: string | null;
  refundedAt: string | null;
}

export interface Notification {
  id: number;
  eventType: string;
  orderId: number;
  message: string;
  createdAt: string;
  sendStatus: 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';
  sentAt: string | null;
  read: boolean;
  readAt: string | null;
}

export interface Settlement {
  id: number;
  storeId: number;
  storeName: string;
  settlementDate: string;
  paymentCount: number;
  totalAmount: number;
  totalFee: number;
  salesSettlementAmount: number;
  refundCount: number;
  refundAmount: number;
  refundFee: number;
  refundSettlementAmount: number;
  carriedInAmount: number;
  carriedOutAmount: number;
  settlementAmount: number;
  status: SettlementStatus;
  aggregatedAt: string;
  confirmedAt: string | null;
  paidAt: string | null;
}

export interface AdminSettlement extends Settlement {
  confirmedBy: { id: number; username: string } | null;
  paidBy: { id: number; username: string } | null;
}

export interface OutboxEvent {
  id: number;
  eventId: string;
  eventType: string;
  aggregateType: string;
  aggregateId: number;
  status: OutboxStatus;
  attemptCount: number;
  lastError: string | null;
  nextAttemptAt: string | null;
  createdAt: string;
  publishedAt: string | null;
}

export type OwnerApplicationStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED';

/** 사장님 전환 신청 (V17 승인제) */
export interface OwnerApplication {
  id: number;
  userId: number;
  username: string;
  businessName: string;
  status: OwnerApplicationStatus;
  rejectReason: string | null;
  requestedAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
}

export interface ApiErrorBody {
  status: number;
  error: string;
  code: string | null;
  message: string;
  path: string;
  traceId: string;
}

/** 사장님 실시간 주문 알림 (SSE `order` 이벤트, #76) — 토스트용. 목록은 API로 다시 받음 */
export interface OwnerOrderEvent {
  notificationId: number;
  type: 'PAYMENT_COMPLETED' | 'PAYMENT_REFUNDED';
  orderId: number;
  storeId: number;
  amount: number;
  message: string;
  occurredAt: string;
}
