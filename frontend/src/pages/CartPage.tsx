import { useCallback, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { orderApi } from '../api/endpoints';
import { DEMO_ADDRESS, validateDeliveryRequest } from '../address/orderExtras';
import { normalizeAddress, validateAddress, type AddressErrors, type AddressInput } from '../address/validateAddress';
import { useCart } from '../cart/CartContext';
import { AddressFields } from '../components/AddressFields';
import { DeliveryRequestField } from '../components/DeliveryRequestField';
import { IconInfo } from '../components/Icons';
import { ErrorMessage } from '../components/ErrorMessage';
import { Money } from '../components/format';

export default function CartPage() {
  const cart = useCart();
  const navigate = useNavigate();
  // 기본은 체험용 주소 — 데모 서비스에 실제 집 주소가 저장되지 않도록. 실제 주소는 '주소 검색'을 골랐을 때만
  const [mode, setMode] = useState<'demo' | 'search'>('demo');
  const [address, setAddress] = useState<AddressInput>({ zipCode: '', roadAddress: '', detailAddress: '' });
  const [deliveryRequest, setDeliveryRequest] = useState('');
  const requestError = validateDeliveryRequest(deliveryRequest);
  // 제출을 한 번 시도한 뒤부터 입력할 때마다 다시 검사 (처음부터 빨간 안내를 띄우지 않도록)
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const target = mode === 'demo' ? DEMO_ADDRESS : address;
  const addressErrors: AddressErrors = touched && mode === 'search' ? validateAddress(address) : {};
  const onAddressChange = useCallback((next: AddressInput) => setAddress(next), []);
  const [submitting, setSubmitting] = useState(false);

  if (cart.lines.length === 0) {
    return (
      <section>
        <h1>장바구니</h1>
        <p className="notice">담은 메뉴가 없습니다.</p>
      </section>
    );
  }

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (Object.keys(validateAddress(target)).length > 0 || requestError) return;
    setSubmitting(true);
    setError(null);
    try {
      const order = await orderApi.create(
        cart.lines.map((l) => ({ menuId: l.menu.id, quantity: l.quantity })),
        normalizeAddress(target),
        deliveryRequest.trim(),
      );
      cart.clear();
      // 주문 생성 후 결제는 주문 상세에서 (주문요청 → 결제 → 결제완료)
      navigate(`/orders/${order.id}`);
    } catch (err) {
      // 서버 주소 검증 실패(INVALID_ADDRESS)는 주소 칸 안내도 함께 보이게
      if (err instanceof ApiError && err.code === 'INVALID_ADDRESS') setTouched(true);
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section>
      <h1>장바구니</h1>
      <ul className="list">
        {cart.lines.map((l) => (
          <li key={l.menu.id} className="card row" style={{ justifyContent: 'space-between' }}>
            <span>
              {l.menu.name} · <Money value={l.menu.price} />
            </span>
            <span className="row">
              <button type="button" className="btn btn--ghost" onClick={() => cart.setQuantity(l.menu.id, l.quantity - 1)}>
                −
              </button>
              <span className="num">{l.quantity}</span>
              <button type="button" className="btn btn--ghost" onClick={() => cart.setQuantity(l.menu.id, l.quantity + 1)}>
                +
              </button>
            </span>
          </li>
        ))}
      </ul>
      <p>
        합계 <Money value={cart.total} /> <span className="muted">(최종 금액은 서버가 계산)</span>
      </p>
      <form className="form" onSubmit={onSubmit}>
        <div className="amode">
          <span className="amode__label" id="address-mode-label">
            배달 주소
          </span>
          <div className="seg" role="group" aria-labelledby="address-mode-label">
            <button type="button" className={mode === 'demo' ? 'on' : ''} aria-pressed={mode === 'demo'} onClick={() => setMode('demo')}>
              체험용 주소
            </button>
            <button type="button" className={mode === 'search' ? 'on' : ''} aria-pressed={mode === 'search'} onClick={() => setMode('search')}>
              주소 검색
            </button>
          </div>
          {mode === 'demo' ? (
            <div className="amode__demo">
              <b>
                <span className="num">({DEMO_ADDRESS.zipCode})</span> {DEMO_ADDRESS.roadAddress}
              </b>
              <p>
                <IconInfo size={16} /> 데모 서비스라 실제로 배달되지 않아요. 개인정보 보호를 위해 실제 집 주소 대신 이 주소로 주문해
                보세요.
              </p>
            </div>
          ) : (
            <>
              <p className="amode__warn">
                <IconInfo size={16} /> 입력한 주소는 주문 기록에 저장되고 매장에 보여요. 데모에서는 실제 집 주소를 넣지 마세요.
              </p>
              <AddressFields value={address} onChange={onAddressChange} errors={addressErrors} />
            </>
          )}
        </div>
        <DeliveryRequestField value={deliveryRequest} onChange={setDeliveryRequest} error={touched ? requestError : null} />
        <ErrorMessage error={error} />
        <button className="btn" disabled={submitting}>
          주문하기
        </button>
      </form>
    </section>
  );
}
