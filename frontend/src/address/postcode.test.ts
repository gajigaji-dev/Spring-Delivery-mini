import { describe, expect, it } from 'vitest';
import { toRoadAddress, type PostcodeData } from './postcode';

function data(over: Partial<PostcodeData>): PostcodeData {
  return {
    zonecode: '06236',
    roadAddress: '서울 강남구 테헤란로 152',
    autoRoadAddress: '',
    userSelectedType: 'R',
    bname: '',
    buildingName: '',
    apartment: 'N',
    ...over,
  };
}

describe('toRoadAddress', () => {
  it('법정동과 아파트 이름을 괄호로 덧붙인다', () => {
    expect(toRoadAddress(data({ bname: '역삼동', buildingName: '래미안', apartment: 'Y' }))).toBe(
      '서울 강남구 테헤란로 152 (역삼동, 래미안)',
    );
  });

  it('아파트가 아닌 건물명과 리 단위 법정동은 붙이지 않는다', () => {
    expect(toRoadAddress(data({ bname: '봉담리', buildingName: '강남파이낸스센터', apartment: 'N' }))).toBe(
      '서울 강남구 테헤란로 152',
    );
  });

  it('지번 주소를 골라도 대응하는 도로명 주소로 저장한다', () => {
    expect(toRoadAddress(data({ userSelectedType: 'J', roadAddress: '', autoRoadAddress: '경기 성남시 분당구 판교역로 166' }))).toBe(
      '경기 성남시 분당구 판교역로 166',
    );
  });
});
