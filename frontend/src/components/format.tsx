import type { OrderStatus, PaymentStatus, SettlementStatus } from '../api/types';

/** 금액은 서버와 같이 정수(원). 디자인 표기는 ₩ 접두 + 자릿수 고정 숫자(tabular-nums) */
export function won(value: number): string {
  return `₩${Math.round(value).toLocaleString('ko-KR')}`;
}

export function Money({ value }: { value: number }) {
  return <span className="num">{won(value)}</span>;
}

/** 서버 시각은 Asia/Seoul 기준 오프셋 없는 문자열 → Date로 바꾸면 브라우저 시간대가 섞이므로 문자열 그대로 자름 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  return value.replace('T', ' ').slice(0, 16);
}

export const orderStatusLabel: Record<OrderStatus, string> = {
  ORDER_REQUEST: '결제 대기',
  PAYMENT_COMPLETED: '결제 완료',
  ORDER_ACCEPTED: '배달 중',
  DELIVERY_COMPLETED: '배달 완료',
  ORDER_CANCELLED: '취소',
};

/** 상태 의미 색 — 대기(행동 필요)·진행·완료·예정·취소 (디자인 03 상태 의미) */
export const orderStatusTone: Record<OrderStatus, Tone> = {
  ORDER_REQUEST: 'warn',
  PAYMENT_COMPLETED: 'wait',
  ORDER_ACCEPTED: 'prog',
  DELIVERY_COMPLETED: 'ok',
  ORDER_CANCELLED: 'neutral',
};

export const paymentStatusLabel: Record<PaymentStatus, string> = {
  COMPLETED: '결제 완료',
  CANCELLED: '결제 취소',
  REFUNDED: '환불',
};

export const settlementStatusLabel: Record<SettlementStatus, string> = {
  PENDING: '정산 예정',
  CONFIRMED: '입금 대기',
  PAID: '지급 완료',
};

export type Tone = 'neutral' | 'ok' | 'warn' | 'danger' | 'prog' | 'wait';

export function StatusBadge({ label, tone = 'neutral' }: { label: string; tone?: Tone }) {
  return <span className={`badge badge--${tone}`}>{label}</span>;
}
