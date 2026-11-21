import type { AddressInput } from './validateAddress';

/**
 * 체험용 주소 — 데모 서비스에 실제 집 주소가 저장되지 않도록 기본으로 쓰는 가짜 주소.
 * 우편번호 00000은 실제 우편번호 체계에 없어 진짜 주소로 오해될 일이 없고, 서버 형식 검증(숫자 5자리)은 통과함.
 */
export const DEMO_ADDRESS: AddressInput = {
  zipCode: '00000',
  roadAddress: '체험용 주소 (실제 배달되지 않음)',
  detailAddress: '',
};

/** 배달 요청사항 — 서버 Order.DELIVERY_REQUEST_MAX와 같은 값 */
export const DELIVERY_REQUEST_MAX = 100;

/** 자주 쓰는 요청 — 눌러서 바로 채움 (직접 입력도 가능) */
export const REQUEST_PRESETS = [
  '문 앞에 두고 벨 눌러 주세요',
  '문 앞에 두고 노크해 주세요',
  '직접 받을게요',
  '벨 누르지 말아 주세요',
];

// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

/** 오류 문구, 문제가 없으면 null — 서버와 같은 규칙(앞뒤 공백 제외 100자, 제어 문자 금지) */
export function validateDeliveryRequest(value: string): string | null {
  const v = value.trim();
  if (v.length > DELIVERY_REQUEST_MAX) return `요청사항은 ${DELIVERY_REQUEST_MAX}자 이하여야 합니다.`;
  if (CONTROL_CHARS.test(v)) return '사용할 수 없는 문자가 있습니다.';
  return null;
}
