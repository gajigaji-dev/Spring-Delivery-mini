import { describe, expect, it } from 'vitest';
import { normalizeAddress, validateAddress } from './validateAddress';

const valid = { zipCode: '06236', roadAddress: '서울 강남구 테헤란로 152', detailAddress: '101호' };

describe('validateAddress', () => {
  it('올바른 주소는 오류가 없고, 상세 주소는 비어도 된다', () => {
    expect(validateAddress(valid)).toEqual({});
    expect(validateAddress({ ...valid, detailAddress: '  ' })).toEqual({});
  });

  it('아무것도 고르지 않았으면 주소 검색을 안내한다', () => {
    expect(validateAddress({ zipCode: '', roadAddress: '', detailAddress: '' })).toEqual({
      roadAddress: '주소 검색으로 배달 주소를 선택해 주세요.',
    });
  });

  it.each(['1234', '123456', '12a45', '135-080'])('우편번호 %s는 거부한다', (zipCode) => {
    expect(validateAddress({ ...valid, zipCode }).zipCode).toBe('우편번호는 숫자 5자리입니다.');
  });

  it('서버와 같은 길이 제한을 쓴다 (도로명 150자, 상세 100자)', () => {
    expect(validateAddress({ ...valid, roadAddress: '가'.repeat(150), detailAddress: '나'.repeat(100) })).toEqual({});
    expect(validateAddress({ ...valid, roadAddress: '가'.repeat(151) }).roadAddress).toBeDefined();
    expect(validateAddress({ ...valid, detailAddress: '나'.repeat(101) }).detailAddress).toBeDefined();
  });

  it('줄바꿈·탭 같은 제어 문자는 거부한다', () => {
    expect(validateAddress({ ...valid, detailAddress: '101동\n1203호' }).detailAddress).toBeDefined();
    expect(validateAddress({ ...valid, roadAddress: '서울\t강남구' }).roadAddress).toBeDefined();
  });

  it('보내기 전에 앞뒤 공백을 지운다', () => {
    expect(normalizeAddress({ zipCode: ' 06236 ', roadAddress: ' 서울 ', detailAddress: ' 101호 ' })).toEqual({
      zipCode: '06236',
      roadAddress: '서울',
      detailAddress: '101호',
    });
  });
});
