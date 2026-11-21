import { ApiError } from './client';

/**
 * 오류 코드(ApiErrorResponse.code)별 사용자 안내 (#77).
 * 서버 message는 개발자용 설명에 가깝고 바뀔 수 있어, 화면 문구는 code로 정함 (docs/api.md "오류 코드").
 * 매핑이 없는 code·code 없는 오류는 상태 코드별 기본 문구 → 그것도 없으면 서버 message.
 */
const CODE_MESSAGES: Record<string, string> = {
  // 주소·주문 입력
  INVALID_ADDRESS: '배달 주소를 확인해 주세요. 주소 검색으로 우편번호와 도로명 주소를 선택해야 합니다.',
  INVALID_DELIVERY_REQUEST: '배달 요청사항을 확인해 주세요. 100자 이하로, 줄바꿈 없이 입력해야 합니다.',
  DUPLICATE_ORDER_ITEM: '같은 메뉴가 여러 번 담겼습니다. 장바구니를 확인해 주세요.',
  MIXED_STORE_ORDER: '한 번에 한 매장의 메뉴만 주문할 수 있습니다.',
  ORDER_AMOUNT_TOO_LARGE: '주문 금액이 너무 큽니다. 수량을 줄여 주세요.',
  MENU_NOT_FOUND: '판매가 끝난 메뉴가 있습니다. 장바구니를 다시 확인해 주세요.',
  ORDER_NOT_FOUND: '주문을 찾을 수 없습니다.',

  // 주문 상태
  ORDER_ALREADY_PAID: '이미 결제된 주문입니다. 결제 내역을 확인해 주세요.',
  ORDER_ALREADY_CANCELLED: '이미 취소된 주문입니다.',
  ORDER_NOT_CANCELLABLE: '매장이 주문을 수락해 취소할 수 없습니다. 매장에 문의해 주세요.',
  INVALID_ORDER_STATUS: '주문 상태가 바뀌었습니다. 화면을 새로 고쳐 확인해 주세요.',

  // 결제
  UNSUPPORTED_PAYMENT_METHOD: '지원하지 않는 결제 수단입니다. 카드 결제를 이용해 주세요.',
  PAYMENT_NOT_FOUND: '결제 내역을 찾을 수 없습니다.',
  PAYMENT_IN_PROGRESS: '결제를 처리하고 있습니다. 잠시 후 다시 확인해 주세요.',
  PAYMENT_UNAVAILABLE: '지금은 결제를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요. (결제되지 않았습니다)',
  IDEMPOTENCY_KEY_REUSED: '결제 요청이 올바르지 않습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.',
  INVALID_IDEMPOTENCY_KEY: '결제 요청이 올바르지 않습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.',
  PAYMENT_ALREADY_CANCELLED: '이미 취소된 결제입니다.',
  PAYMENT_ALREADY_REFUNDED: '이미 환불된 결제입니다.',
  PAYMENT_NOT_REFUNDABLE: '배달 완료 전 결제는 환불 대신 주문 취소를 이용해 주세요.',
  INVALID_PAYMENT_STATUS: '결제 상태가 바뀌었습니다. 화면을 새로 고쳐 확인해 주세요.',

  // 토스페이먼츠 (V20)
  PAYMENT_AMOUNT_MISMATCH: '결제 금액이 주문 금액과 달라 결제하지 않았습니다. 주문에서 다시 결제해 주세요.',
  INVALID_PG_ORDER_ID: '결제 정보가 이 주문과 맞지 않습니다. 주문에서 다시 결제해 주세요.',
  PG_PAYMENT_REJECTED: '결제가 승인되지 않았습니다. 카드 정보를 확인하고 다시 결제해 주세요.',
  PG_NOT_CONFIGURED: '지금은 토스 결제를 사용할 수 없습니다. (결제되지 않았습니다)',
  INVALID_REFUND_AMOUNT: '환불 금액이 남은 결제 금액보다 큽니다.',
  PG_RESULT_UNKNOWN: '결제 결과를 확인하고 있습니다. 결제창을 다시 열지 말고 잠시 후 다시 확인해 주세요.',
};

const STATUS_MESSAGES: Record<number, string> = {
  503: '잠시 후 다시 시도해 주세요. (요청은 반영되지 않았습니다)',
};

/** 화면에 보여 줄 문구 (문의 코드는 붙이지 않음) */
export function userMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '네트워크 오류가 발생했습니다. 연결을 확인한 뒤 다시 시도해 주세요.';
  if (error.code && CODE_MESSAGES[error.code]) return CODE_MESSAGES[error.code];
  if (error.status >= 500 && error.status !== 503) return '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
  return STATUS_MESSAGES[error.status] ?? error.message;
}

/**
 * 주문 상태가 이미 바뀐 오류 — 다시 눌러도 같은 결과라 화면을 최신 상태로 다시 불러와야 함.
 * 결제라면 저장해 둔 Idempotency-Key도 버림 (이 주문으로 다시 결제할 일이 없음).
 */
export function isStaleOrderState(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 409 &&
    ['ORDER_ALREADY_PAID', 'ORDER_ALREADY_CANCELLED', 'ORDER_NOT_CANCELLABLE', 'INVALID_ORDER_STATUS'].includes(
      error.code ?? '',
    )
  );
}
