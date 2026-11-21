import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { orderApi, paymentApi } from '../api/endpoints';
import { isStaleOrderState } from '../api/errorMessages';
import { useAuth } from '../auth/AuthContext';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatDateTime, Money, orderStatusLabel, paymentStatusLabel, StatusBadge } from '../components/format';
import { useAsync } from '../hooks/useAsync';

// 결제 응답을 못 받은 채(타임아웃·새로고침) 다시 누르면 같은 키로 보내야 이중 결제가 안 됨
// → 주문별로 키를 sessionStorage에 두고, 결제가 끝나면 지움 (docs/api.md 멱등성 키)
function idempotencyKeyFor(orderId: number): string {
  const storageKey = `pay-key:${orderId}`;
  try {
    const saved = sessionStorage.getItem(storageKey);
    if (saved) return saved;
    const key = crypto.randomUUID();
    sessionStorage.setItem(storageKey, key);
    return key;
  } catch {
    return crypto.randomUUID();
  }
}

function forgetIdempotencyKey(orderId: number): void {
  try {
    sessionStorage.removeItem(`pay-key:${orderId}`);
  } catch {
    // 저장소를 못 쓰는 환경 — 지울 것도 없음
  }
}

export default function OrderDetailPage() {
  const orderId = Number(useParams().id);
  const { me } = useAuth();
  const { data: order, error, loading, reload } = useAsync(() => orderApi.get(orderId), [orderId]);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  // after: 성공했거나 주문 상태가 이미 바뀌어(409 이미 결제·취소됨) 다시 할 일이 없을 때 실행
  const run = async (action: () => Promise<unknown>, after?: () => void) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      after?.();
      reload();
    } catch (err) {
      setActionError(err);
      // 다른 탭·기기에서 이미 결제·취소된 경우 — 버튼이 최신 상태와 맞도록 주문을 다시 불러옴 (#77)
      if (isStaleOrderState(err)) {
        after?.();
        reload();
      }
    } finally {
      setBusy(false);
    }
  };

  if (loading && !order) return <DelayedSpinner />;
  if (!order) return <ErrorMessage error={error} />;

  const isCustomer = me?.role === 'CUSTOMER';
  const isOwner = me?.role === 'OWNER';

  return (
    <section>
      <h1>
        주문 #{order.id} <StatusBadge label={orderStatusLabel[order.status]} />
      </h1>
      <p>
        {order.storeName} · {order.zipCode && <span className="muted num">({order.zipCode}) </span>}
        {order.deliveryAddress}
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>메뉴</th>
              <th className="amount">단가</th>
              <th className="amount">수량</th>
              <th className="amount">금액</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((i) => (
              <tr key={i.menuId}>
                <td>{i.menuName}</td>
                <td className="amount"><Money value={i.unitPrice} /></td>
                <td className="amount num">{i.quantity}</td>
                <td className="amount"><Money value={i.linePrice} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>
        합계 <strong><Money value={order.totalPrice} /></strong>
      </p>
      <p className="muted">
        주문 {formatDateTime(order.createdAt)} · 배달완료 {formatDateTime(order.deliveredAt)}
      </p>

      <h2>결제</h2>
      {order.payment ? (
        <p>
          <StatusBadge label={paymentStatusLabel[order.payment.status]} /> <Money value={order.payment.amount} /> ·{' '}
          {formatDateTime(order.payment.paidAt)}
        </p>
      ) : (
        <p className="notice">결제 전입니다.</p>
      )}

      <ErrorMessage error={actionError} />
      <div className="row">
        {isCustomer && order.status === 'ORDER_REQUEST' && (
          <button
            type="button"
            className="btn"
            disabled={busy}
            onClick={() => run(() => paymentApi.pay(order.id, idempotencyKeyFor(order.id)), () => forgetIdempotencyKey(order.id))}
          >
            카드 결제
          </button>
        )}
        {/* 취소는 주문수락 전까지만 (이후는 409, 배달완료 뒤에는 관리자 환불) */}
        {isCustomer && (order.status === 'ORDER_REQUEST' || order.status === 'PAYMENT_COMPLETED') && (
          <button type="button" className="btn btn--danger" disabled={busy} onClick={() => run(() => orderApi.cancel(order.id))}>
            주문 취소
          </button>
        )}
        {isOwner && order.status === 'PAYMENT_COMPLETED' && (
          <button type="button" className="btn" disabled={busy} onClick={() => run(() => orderApi.changeStatus(order.id, 'ORDER_ACCEPTED'))}>
            주문 수락
          </button>
        )}
        {isOwner && order.status === 'ORDER_ACCEPTED' && (
          <button
            type="button"
            className="btn"
            disabled={busy}
            onClick={() => run(() => orderApi.changeStatus(order.id, 'DELIVERY_COMPLETED'))}
          >
            배달 완료
          </button>
        )}
      </div>
    </section>
  );
}
