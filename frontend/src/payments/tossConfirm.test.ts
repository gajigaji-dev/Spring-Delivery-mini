import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { confirmWithRetry, failMessage, isRetryableConfirmError, parseFailParams, parseSuccessParams } from './tossConfirm';
import { fakeTossCheckoutPath, tossReturnUrls, withTossFailParams, withTossSuccessParams } from './tossSdk';

describe('parseSuccessParams', () => {
  it('토스가 붙인 값과 이 서비스의 주문 id를 함께 읽는다', () => {
    expect(parseSuccessParams('?order=12&paymentKey=pk_1&orderId=order-12-0123456789abcdef&amount=36000')).toEqual({
      orderId: 12,
      paymentKey: 'pk_1',
      tossOrderId: 'order-12-0123456789abcdef',
      amount: 36000,
    });
  });

  it('값이 빠지거나 숫자가 아니면 승인을 요청하지 않도록 null', () => {
    expect(parseSuccessParams('?order=12&orderId=x&amount=36000')).toBeNull();
    expect(parseSuccessParams('?order=abc&paymentKey=pk&orderId=x&amount=36000')).toBeNull();
    expect(parseSuccessParams('?order=12&paymentKey=pk&orderId=x&amount=-1')).toBeNull();
  });
});

describe('결제창 실패', () => {
  it('구매자가 창을 닫으면 취소 안내를 보여 준다', () => {
    const params = parseFailParams('?order=3&code=PAY_PROCESS_CANCELED&message=x');
    expect(params.orderId).toBe(3);
    expect(failMessage(params)).toBe('결제를 취소했습니다. 결제되지 않았습니다.');
  });

  it('그 밖의 실패는 토스 문구를 함께 보여 준다', () => {
    expect(failMessage(parseFailParams('?code=REJECT_CARD_COMPANY&message=한도 초과'))).toBe('결제하지 못했습니다: 한도 초과');
  });
});

describe('isRetryableConfirmError', () => {
  it('결과를 모르는 오류·네트워크 오류만 같은 요청으로 재시도한다', () => {
    expect(isRetryableConfirmError(new ApiError(503, '', 'PG_RESULT_UNKNOWN', null))).toBe(true);
    expect(isRetryableConfirmError(new ApiError(409, '', 'PAYMENT_IN_PROGRESS', null))).toBe(true);
    expect(isRetryableConfirmError(new TypeError('Failed to fetch'))).toBe(true);
    expect(isRetryableConfirmError(new ApiError(400, '', 'PAYMENT_AMOUNT_MISMATCH', null))).toBe(false);
    expect(isRetryableConfirmError(new ApiError(400, '', 'PG_PAYMENT_REJECTED', null))).toBe(false);
  });
});

describe('confirmWithRetry', () => {
  it('결과를 모르면 정해진 간격으로 다시 시도해 성공 결과를 돌려준다', async () => {
    const confirm = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new ApiError(503, '', 'PG_RESULT_UNKNOWN', null))
      .mockResolvedValueOnce('ok');
    const sleep = vi.fn(() => Promise.resolve());

    await expect(confirmWithRetry(confirm, [10, 20], sleep)).resolves.toBe('ok');
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(sleep).toHaveBeenCalledWith(10);
  });

  it('거절은 재시도하지 않고 바로 던진다', async () => {
    const rejected = new ApiError(400, '', 'PG_PAYMENT_REJECTED', null);
    const confirm = vi.fn<() => Promise<string>>().mockRejectedValue(rejected);

    await expect(confirmWithRetry(confirm, [10], () => Promise.resolve())).rejects.toBe(rejected);
    expect(confirm).toHaveBeenCalledTimes(1);
  });

  it('재시도를 다 써도 모르면 마지막 오류를 던진다', async () => {
    const unknown = new ApiError(503, '', 'PG_RESULT_UNKNOWN', null);
    const confirm = vi.fn<() => Promise<string>>().mockRejectedValue(unknown);

    await expect(confirmWithRetry(confirm, [1, 1], () => Promise.resolve())).rejects.toBe(unknown);
    expect(confirm).toHaveBeenCalledTimes(3);
  });
});

describe('tossReturnUrls', () => {
  it('주문 id는 토스 orderId와 겹치지 않게 order로 붙인다', () => {
    expect(tossReturnUrls('http://localhost:5173', 7)).toEqual({
      successUrl: 'http://localhost:5173/payments/toss/success?order=7',
      failUrl: 'http://localhost:5173/payments/toss/fail?order=7',
    });
  });
});

describe('가짜 결제창 (V21)', () => {
  it('가짜 결제창이 붙인 쿼리를 성공 화면이 토스와 똑같이 읽는다', () => {
    const { successUrl } = tossReturnUrls('http://localhost:5173', 7);
    const url = new URL(withTossSuccessParams(successUrl, 'fake_abc', 'order-7-0123456789abcdef', 36000));

    expect(url.pathname).toBe('/payments/toss/success');
    expect(parseSuccessParams(url.search)).toEqual({
      orderId: 7,
      paymentKey: 'fake_abc',
      tossOrderId: 'order-7-0123456789abcdef',
      amount: 36000,
    });
  });

  it('결제창 닫기는 실패 화면에 취소 코드를 넘긴다', () => {
    const { failUrl } = tossReturnUrls('http://localhost:5173', 7);
    const url = new URL(withTossFailParams(failUrl, 'PAY_PROCESS_CANCELED', '취소', 'order-7-x'));

    expect(failMessage(parseFailParams(url.search))).toBe('결제를 취소했습니다. 결제되지 않았습니다.');
  });

  it('가짜 결제창 주소에 서버가 정한 금액·주문번호를 그대로 넘긴다', () => {
    const path = fakeTossCheckoutPath(
      { clientKey: 'fake_ck', customerKey: 'cust_x', tossOrderId: 'order-7-x', amount: 36000, orderName: '치킨 외 1건' },
      7,
    );
    const q = new URLSearchParams(path.split('?')[1]);

    expect(path.startsWith('/payments/fake-toss?')).toBe(true);
    expect(q.get('amount')).toBe('36000');
    expect(q.get('orderName')).toBe('치킨 외 1건');
  });
});
