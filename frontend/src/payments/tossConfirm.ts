import { ApiError } from '../api/client';

/** successUrl 쿼리 — 토스가 paymentKey·orderId(토스 주문번호)·amount를, 이 서비스가 order(주문 id)를 붙임 */
export interface TossSuccessParams {
  orderId: number;
  paymentKey: string;
  tossOrderId: string;
  amount: number;
}

export function parseSuccessParams(search: string): TossSuccessParams | null {
  const q = new URLSearchParams(search);
  const orderId = Number(q.get('order'));
  const amount = Number(q.get('amount'));
  const paymentKey = q.get('paymentKey');
  const tossOrderId = q.get('orderId');
  if (!Number.isSafeInteger(orderId) || orderId <= 0) return null;
  if (!Number.isSafeInteger(amount) || amount <= 0) return null;
  if (!paymentKey || !tossOrderId) return null;
  return { orderId, paymentKey, tossOrderId, amount };
}

/** failUrl 쿼리 — 토스가 code·message를 붙임 (구매자가 창을 닫으면 PAY_PROCESS_CANCELED) */
export interface TossFailParams {
  orderId: number | null;
  code: string | null;
  message: string | null;
}

export function parseFailParams(search: string): TossFailParams {
  const q = new URLSearchParams(search);
  const orderId = Number(q.get('order'));
  return {
    orderId: Number.isSafeInteger(orderId) && orderId > 0 ? orderId : null,
    code: q.get('code'),
    message: q.get('message'),
  };
}

/** 결제창 실패 안내 — 토스 message는 그대로 보여 줘도 되는 사용자용 문구라 함께 표시 */
export function failMessage(params: TossFailParams): string {
  if (params.code === 'PAY_PROCESS_CANCELED') return '결제를 취소했습니다. 결제되지 않았습니다.';
  return params.message ? `결제하지 못했습니다: ${params.message}` : '결제하지 못했습니다. 결제되지 않았습니다.';
}

/**
 * 같은 승인 요청을 다시 보내도 되는 오류 — 결과를 모름(PG_RESULT_UNKNOWN)·같은 키 처리 중(PAYMENT_IN_PROGRESS)·
 * 네트워크 오류(응답을 못 받음). 결제창을 다시 열면 새 결제가 되므로, 이 경우에는 같은 요청만 재시도함
 * (서버·토스 모두 멱등 키로 승인을 한 번만 처리).
 */
export function isRetryableConfirmError(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true;
  return error.code === 'PG_RESULT_UNKNOWN' || error.code === 'PAYMENT_IN_PROGRESS' || error.code === 'PAYMENT_UNAVAILABLE';
}

/** 재시도 가능한 오류면 delays 간격으로 다시 시도. 마지막 오류는 그대로 던짐 */
export async function confirmWithRetry<T>(
  confirm: () => Promise<T>,
  delays: number[] = [1000, 2000, 4000],
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await confirm();
    } catch (error) {
      if (attempt >= delays.length || !isRetryableConfirmError(error)) throw error;
      await sleep(delays[attempt]);
    }
  }
}
