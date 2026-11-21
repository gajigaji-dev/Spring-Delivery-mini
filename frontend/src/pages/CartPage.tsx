import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { orderApi } from '../api/endpoints';
import { useCart } from '../cart/CartContext';
import { ErrorMessage } from '../components/ErrorMessage';
import { Money } from '../components/format';

export default function CartPage() {
  const cart = useCart();
  const navigate = useNavigate();
  const [address, setAddress] = useState('');
  const [error, setError] = useState<unknown>(null);
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
    setSubmitting(true);
    setError(null);
    try {
      const order = await orderApi.create(
        cart.lines.map((l) => ({ menuId: l.menu.id, quantity: l.quantity })),
        address,
      );
      cart.clear();
      // 주문 생성 후 결제는 주문 상세에서 (주문요청 → 결제 → 결제완료)
      navigate(`/orders/${order.id}`);
    } catch (err) {
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
        <label>
          배달 주소
          <input value={address} onChange={(e) => setAddress(e.target.value)} maxLength={255} required />
        </label>
        <ErrorMessage error={error} />
        <button className="btn" disabled={submitting}>
          주문하기
        </button>
      </form>
    </section>
  );
}
