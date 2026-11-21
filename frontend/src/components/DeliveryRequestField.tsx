import { DELIVERY_REQUEST_MAX, REQUEST_PRESETS } from '../address/orderExtras';

interface Props {
  value: string;
  onChange: (value: string) => void;
  error: string | null;
}

/** 배달 요청사항 (선택) — 자주 쓰는 문구 칩 + 직접 입력. 같은 칩을 다시 누르면 지움 */
export function DeliveryRequestField({ value, onChange, error }: Props) {
  const length = value.trim().length;
  return (
    <div className="dreq">
      <label htmlFor="delivery-request">
        <span>
          배달 요청사항 <span className="muted">(선택)</span>
        </span>
      </label>
      <div className="dreq__chips" role="group" aria-label="자주 쓰는 요청사항">
        {REQUEST_PRESETS.map((p) => (
          <button
            key={p}
            type="button"
            className="dreq__chip"
            aria-pressed={value.trim() === p}
            onClick={() => onChange(value.trim() === p ? '' : p)}
          >
            {p}
          </button>
        ))}
      </div>
      <div className="dreq__input">
        <input
          id="delivery-request"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          maxLength={DELIVERY_REQUEST_MAX}
          placeholder="예) 아이가 자고 있어요. 노크만 해 주세요"
          aria-invalid={!!error}
          aria-describedby="delivery-request-count"
        />
        <span id="delivery-request-count" className="dreq__count num" aria-live="polite">
          {length}/{DELIVERY_REQUEST_MAX}
        </span>
      </div>
      {error && (
        <p className="dreq__error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
