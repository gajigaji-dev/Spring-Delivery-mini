/**
 * 배송 주소 입력 검증 (#77) — 서버 entity.DeliveryAddress와 같은 규칙.
 * 서버가 최종 판단(400 INVALID_ADDRESS)하지만, 제출 전에 막아 왕복 없이 바로 안내하기 위함.
 */
export const ROAD_ADDRESS_MAX = 150;
export const DETAIL_ADDRESS_MAX = 100;

export interface AddressInput {
  zipCode: string;
  roadAddress: string;
  detailAddress: string;
}

export type AddressErrors = Partial<Record<keyof AddressInput, string>>;

// 줄바꿈·탭 등 제어 문자 (서버 \p{Cntrl}와 같은 범위)
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

/** 앞뒤 공백을 지운 값 — 서버도 같은 방식으로 저장하므로 미리 맞춰 보냄 */
export function normalizeAddress(input: AddressInput): AddressInput {
  return {
    zipCode: input.zipCode.trim(),
    roadAddress: input.roadAddress.trim(),
    detailAddress: input.detailAddress.trim(),
  };
}

/** 오류가 없으면 빈 객체 */
export function validateAddress(input: AddressInput): AddressErrors {
  const { zipCode, roadAddress, detailAddress } = normalizeAddress(input);
  const errors: AddressErrors = {};

  if (!roadAddress && !zipCode) {
    errors.roadAddress = '주소 검색으로 배달 주소를 선택해 주세요.';
  } else {
    if (!/^[0-9]{5}$/.test(zipCode)) errors.zipCode = '우편번호는 숫자 5자리입니다.';
    if (!roadAddress) errors.roadAddress = '도로명 주소를 입력해 주세요.';
    else if (roadAddress.length > ROAD_ADDRESS_MAX) errors.roadAddress = `도로명 주소는 ${ROAD_ADDRESS_MAX}자 이하여야 합니다.`;
    else if (CONTROL_CHARS.test(roadAddress)) errors.roadAddress = '사용할 수 없는 문자가 있습니다.';
  }

  if (detailAddress.length > DETAIL_ADDRESS_MAX) errors.detailAddress = `상세 주소는 ${DETAIL_ADDRESS_MAX}자 이하여야 합니다.`;
  else if (CONTROL_CHARS.test(detailAddress)) errors.detailAddress = '사용할 수 없는 문자가 있습니다.';

  return errors;
}
