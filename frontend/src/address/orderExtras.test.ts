import { describe, expect, it } from 'vitest';
import { DEMO_ADDRESS, validateDeliveryRequest } from './orderExtras';
import { validateAddress } from './validateAddress';

describe('체험용 주소', () => {
  it('서버와 같은 주소 규칙을 통과한다 (우편번호 숫자 5자리)', () => {
    expect(validateAddress(DEMO_ADDRESS)).toEqual({});
  });
});

describe('validateDeliveryRequest', () => {
  it('비어 있거나 100자 이하면 통과', () => {
    expect(validateDeliveryRequest('')).toBeNull();
    expect(validateDeliveryRequest('가'.repeat(100))).toBeNull();
    expect(validateDeliveryRequest(`  ${'가'.repeat(100)}  `)).toBeNull(); // 앞뒤 공백은 서버도 지움
  });

  it('100자를 넘거나 제어 문자가 있으면 오류', () => {
    expect(validateDeliveryRequest('가'.repeat(101))).toContain('100자');
    expect(validateDeliveryRequest('문 앞에\t두세요')).not.toBeNull();
  });
});
