import type { OrderStatus, PaymentStatus, SettlementStatus } from '../api/types';

/** 금액은 서버와 같이 정수(원). 자릿수 고정 숫자(tabular-nums)로 표시해 값이 바뀔 때 폭이 흔들리지 않게 함 */
export function Money({ value }: { value: number }) {
  return <span className="num">{value.toLocaleString('ko-KR')}원</span>;
}

/** 서버 시각은 Asia/Seoul 기준 오프셋 없는 문자열 → Date로 바꾸면 브라우저 시간대가 섞이므로 문자열 그대로 자름 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  return value.replace('T', ' ').slice(0, 16);
}

export const orderStatusLabel: Record<OrderStatus, string> = {
  ORDER_REQUEST: '주문요청',
  PAYMENT_COMPLETED: '결제완료',
  ORDER_ACCEPTED: '주문수락',
  DELIVERY_COMPLETED: '배달완료',
  ORDER_CANCELLED: '주문취소',
};

export const paymentStatusLabel: Record<PaymentStatus, string> = {
  COMPLETED: '결제완료',
  CANCELLED: '결제취소',
  REFUNDED: '환불',
};

export const settlementStatusLabel: Record<SettlementStatus, string> = {
  PENDING: '확정 대기',
  CONFIRMED: '확정',
  PAID: '지급완료',
};

export function StatusBadge({ label, tone = 'neutral' }: { label: string; tone?: 'neutral' | 'ok' | 'warn' | 'danger' }) {
  return <span className={`badge badge--${tone}`}>{label}</span>;
}
