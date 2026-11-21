import { useCallback, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { orderApi } from '../api/endpoints';
import { normalizeAddress, validateAddress, type AddressErrors, type AddressInput } from '../address/validateAddress';
import { useCart } from '../cart/CartContext';
import { AddressFields } from '../components/AddressFields';
import { ErrorMessage } from '../components/ErrorMessage';
import { Money } from '../components/format';

export default function CartPage() {
  const cart = useCart();
  const navigate = useNavigate();
  const [address, setAddress] = useState<AddressInput>({ zipCode: '', roadAddress: '', detailAddress: '' });
  // 제출을 한 번 시도한 뒤부터 입력할 때마다 다시 검사 (처음부터 빨간 안내를 띄우지 않도록)
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const addressErrors: AddressErrors = touched ? validateAddress(address) : {};
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
    if (Object.keys(validateAddress(address)).length > 0) return;
    setSubmitting(true);
    setError(null);
    try {
      const order = await orderApi.create(
        cart.lines.map((l) => ({ menuId: l.menu.id, quantity: l.quantity })),
        normalizeAddress(address),
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
        <AddressFields value={address} onChange={onAddressChange} errors={addressErrors} />
        <ErrorMessage error={error} />
        <button className="btn" disabled={submitting}>
          주문하기
        </button>
      </form>
    </section>
  );
}
