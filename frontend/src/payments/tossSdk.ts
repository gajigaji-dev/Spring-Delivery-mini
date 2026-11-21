import type { TossCheckout } from '../api/types';

/**
 * 토스페이먼츠 결제창 SDK v2 (V20, 테스트 모드) — https://docs.tosspayments.com/sdk/v2/js
 * 결제 버튼을 누를 때 처음 한 번만 불러옴 (모든 화면의 첫 로딩에 외부 스크립트를 넣지 않도록, 우편번호 스크립트와 같은 방식).
 * 결제창은 리다이렉트 방식: 성공·실패 시 successUrl·failUrl로 이동하고 이 페이지의 상태는 사라짐
 * → 승인에 필요한 값은 모두 URL 쿼리로 돌아옴 (paymentKey·orderId·amount / code·message·orderId).
 */
const SCRIPT_URL = 'https://js.tosspayments.com/v2/standard';
const LOAD_TIMEOUT_MS = 10_000;

interface TossPaymentInstance {
  requestPayment(request: {
    method: 'CARD';
    amount: { currency: 'KRW'; value: number };
    orderId: string;
    orderName: string;
    successUrl: string;
    failUrl: string;
    card?: { useEscrow?: boolean; flowMode?: 'DEFAULT' | 'DIRECT'; useCardPoint?: boolean; useAppCardOnly?: boolean };
  }): Promise<void>;
}

type TossPaymentsFactory = (clientKey: string) => {
  payment(options: { customerKey: string }): TossPaymentInstance;
};

declare global {
  interface Window {
    TossPayments?: TossPaymentsFactory;
  }
}

let loading: Promise<TossPaymentsFactory> | null = null;

/** 스크립트를 한 번만 불러옴. 실패하면 다음 호출에서 다시 시도 */
export function loadTossPayments(): Promise<TossPaymentsFactory> {
  if (window.TossPayments) return Promise.resolve(window.TossPayments);
  if (loading) return loading;

  loading = new Promise<TossPaymentsFactory>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = SCRIPT_URL;
    script.async = true;
    const timer = setTimeout(() => fail(new Error('결제 모듈을 불러오지 못했습니다.')), LOAD_TIMEOUT_MS);
    function fail(error: Error) {
      clearTimeout(timer);
      script.remove();
      loading = null;
      reject(error);
    }
    script.onload = () => {
      clearTimeout(timer);
      if (window.TossPayments) resolve(window.TossPayments);
      else fail(new Error('결제 모듈을 불러오지 못했습니다.'));
    };
    script.onerror = () => fail(new Error('결제 모듈을 불러오지 못했습니다.'));
    document.head.appendChild(script);
  });
  return loading;
}

/**
 * 돌아올 주소 — 이 서비스의 주문 id는 `order`로 붙임.
 * 토스가 `orderId`(토스 주문번호)를 따로 붙이므로 이름이 겹치지 않게 함.
 */
export function tossReturnUrls(origin: string, orderId: number): { successUrl: string; failUrl: string } {
  return {
    successUrl: `${origin}/payments/toss/success?order=${orderId}`,
    failUrl: `${origin}/payments/toss/fail?order=${orderId}`,
  };
}

/**
 * 가짜 결제창 주소 (서버 toss.payments.mode=fake, V21) — 토스 SDK를 불러오지 않고 이 앱의 /payments/fake-toss 화면으로 이동.
 * 실제 결제창처럼 결제 값은 쿼리로 넘기고, 결과는 같은 successUrl·failUrl로 돌아감 → 성공·실패 화면과 서버 승인은 실제와 같음
 */
export function fakeTossCheckoutPath(checkout: TossCheckout, orderId: number): string {
  const q = new URLSearchParams({
    order: String(orderId),
    tossOrderId: checkout.tossOrderId,
    amount: String(checkout.amount),
    orderName: checkout.orderName,
  });
  return `/payments/fake-toss?${q.toString()}`;
}

/** 결제창이 돌아갈 때 붙이는 쿼리 — 토스와 같은 이름(paymentKey·orderId·amount / code·message·orderId) */
export function withTossSuccessParams(successUrl: string, paymentKey: string, tossOrderId: string, amount: number): string {
  const q = new URLSearchParams({ paymentKey, orderId: tossOrderId, amount: String(amount) });
  return `${successUrl}&${q.toString()}`;
}

export function withTossFailParams(failUrl: string, code: string, message: string, tossOrderId: string): string {
  const q = new URLSearchParams({ code, message, orderId: tossOrderId });
  return `${failUrl}&${q.toString()}`;
}

/** 카드 결제창 열기 — 성공하면 페이지가 이동하므로 이 Promise는 보통 끝나지 않음 */
export async function openTossCardPayment(checkout: TossCheckout, orderId: number): Promise<void> {
  const TossPayments = await loadTossPayments();
  const payment = TossPayments(checkout.clientKey).payment({ customerKey: checkout.customerKey });
  await payment.requestPayment({
    method: 'CARD',
    amount: { currency: 'KRW', value: checkout.amount },
    orderId: checkout.tossOrderId,
    orderName: checkout.orderName,
    ...tossReturnUrls(window.location.origin, orderId),
    card: { useEscrow: false, flowMode: 'DEFAULT', useCardPoint: false, useAppCardOnly: false },
  });
}
