import { useEffect, useRef, useState } from 'react';
import { loadPostcode, toRoadAddress } from '../address/postcode';
import { DETAIL_ADDRESS_MAX, ROAD_ADDRESS_MAX, type AddressErrors, type AddressInput } from '../address/validateAddress';

interface Props {
  value: AddressInput;
  onChange: (value: AddressInput) => void;
  errors: AddressErrors;
}

/**
 * 배송 주소 입력 (#77) — 우편번호·도로명은 다음 우편번호 검색 결과로 채우고, 상세 주소만 직접 입력.
 * 검색 창은 팝업 대신 화면 안에 띄움(embed): 모바일·팝업 차단에서도 동작.
 * 검색 스크립트를 못 불러오면(차단·오프라인) 우편번호·도로명을 직접 입력할 수 있게 열어 줌 — 주문이 막히지 않도록.
 */
export function AddressFields({ value, onChange, errors }: Props) {
  const [searching, setSearching] = useState(false);
  const [manual, setManual] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const layerRef = useRef<HTMLDivElement>(null);
  const detailRef = useRef<HTMLInputElement>(null);
  // embed 콜백이 최신 value를 쓰도록 (검색 중에 상세 주소를 바꿔도 덮어쓰지 않음)
  const valueRef = useRef(value);
  valueRef.current = value;

  useEffect(() => {
    if (!searching) return;
    let cancelled = false;
    loadPostcode()
      .then((Postcode) => {
        if (cancelled || !layerRef.current) return;
        new Postcode({
          width: '100%',
          height: '100%',
          oncomplete: (data) => {
            onChange({ ...valueRef.current, zipCode: data.zonecode, roadAddress: toRoadAddress(data) });
            setSearching(false);
            // 주소를 고른 다음 할 일은 상세 주소 입력
            setTimeout(() => detailRef.current?.focus(), 0);
          },
        }).embed(layerRef.current, { autoClose: false });
      })
      .catch(() => {
        if (cancelled) return;
        setSearching(false);
        setLoadError(true);
        setManual(true);
      });
    return () => {
      cancelled = true;
    };
  }, [searching, onChange]);

  return (
    <fieldset className="address">
      <legend>배달 주소</legend>
      <div className="address__row">
        <label className="address__zip">
          우편번호
          <input
            value={value.zipCode}
            onChange={(e) => onChange({ ...value, zipCode: e.target.value })}
            readOnly={!manual}
            inputMode="numeric"
            maxLength={5}
            placeholder="00000"
            aria-invalid={!!errors.zipCode}
          />
        </label>
        <button type="button" className="btn btn--ghost" onClick={() => setSearching((s) => !s)}>
          {searching ? '검색 닫기' : '주소 검색'}
        </button>
      </div>
      {searching && <div ref={layerRef} className="address__layer" aria-label="주소 검색" />}
      {loadError && (
        <p className="muted" role="status">
          주소 검색을 불러오지 못했습니다. 우편번호와 도로명 주소를 직접 입력해 주세요.
        </p>
      )}
      <label>
        도로명 주소
        <input
          value={value.roadAddress}
          onChange={(e) => onChange({ ...value, roadAddress: e.target.value })}
          readOnly={!manual}
          maxLength={ROAD_ADDRESS_MAX}
          placeholder={manual ? '예) 서울 강남구 테헤란로 152' : '주소 검색으로 선택'}
          onClick={() => !manual && !value.roadAddress && setSearching(true)}
          aria-invalid={!!errors.roadAddress}
        />
      </label>
      <label>
        상세 주소 <span className="muted">(선택)</span>
        <input
          ref={detailRef}
          value={value.detailAddress}
          onChange={(e) => onChange({ ...value, detailAddress: e.target.value })}
          maxLength={DETAIL_ADDRESS_MAX}
          placeholder="동·호수 등"
          aria-invalid={!!errors.detailAddress}
        />
      </label>
      {(errors.zipCode || errors.roadAddress || errors.detailAddress) && (
        <ul className="address__errors" role="alert">
          {[errors.zipCode, errors.roadAddress, errors.detailAddress].filter(Boolean).map((m) => (
            <li key={m}>{m}</li>
          ))}
        </ul>
      )}
    </fieldset>
  );
}
