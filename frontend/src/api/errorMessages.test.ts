import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import { isStaleOrderState, userMessage } from './errorMessages';

describe('userMessage', () => {
  it('오류 코드가 있으면 서버 문구 대신 코드별 안내를 보여 준다', () => {
    expect(userMessage(new ApiError(409, '이미 결제된 주문입니다.', 'ORDER_ALREADY_PAID', null))).toBe(
      '이미 결제된 주문입니다. 결제 내역을 확인해 주세요.',
    );
    expect(userMessage(new ApiError(503, '...', 'PAYMENT_UNAVAILABLE', null))).toContain('결제되지 않았습니다');
  });

  it('모르는 코드나 코드 없는 오류는 서버 문구를 그대로 쓴다', () => {
    expect(userMessage(new ApiError(403, '본인의 주문만 결제할 수 있습니다.', null, null))).toBe('본인의 주문만 결제할 수 있습니다.');
    expect(userMessage(new ApiError(400, '서버 문구', 'SOMETHING_NEW', null))).toBe('서버 문구');
  });

  it('코드 없는 503·500은 상태 코드별 기본 문구, 네트워크 오류는 연결 확인 안내', () => {
    expect(userMessage(new ApiError(503, 'DB 지연', null, null))).toBe('잠시 후 다시 시도해 주세요. (요청은 반영되지 않았습니다)');
    expect(userMessage(new ApiError(500, '서버 내부 오류', null, null))).toContain('일시적인 오류');
    expect(userMessage(new TypeError('Failed to fetch'))).toContain('네트워크 오류');
  });
});

describe('isStaleOrderState', () => {
  it('이미 결제·취소된 주문 409만 다시 불러올 대상이다', () => {
    expect(isStaleOrderState(new ApiError(409, '', 'ORDER_ALREADY_PAID', null))).toBe(true);
    expect(isStaleOrderState(new ApiError(409, '', 'ORDER_ALREADY_CANCELLED', null))).toBe(true);
    // 처리 중(같은 키 재시도 대상)은 키를 버리면 이중 결제 위험 → 아님
    expect(isStaleOrderState(new ApiError(409, '', 'PAYMENT_IN_PROGRESS', null))).toBe(false);
    expect(isStaleOrderState(new ApiError(503, '', 'PAYMENT_UNAVAILABLE', null))).toBe(false);
  });
});
