import { useEffect, useState } from 'react';
import { orderApi } from '../api/endpoints';
import type { Order } from '../api/types';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatDateTime, Money, orderStatusLabel, StatusBadge } from '../components/format';
import { PrefetchLink } from '../components/PrefetchLink';

/** keyset "더 보기" — 넘기는 중 새 주문이 들어와도 이미 본 주문이 다시 나오지 않음 */
export default function OrdersPage() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [afterId, setAfterId] = useState<number | null>(null);
  const [nextAfterId, setNextAfterId] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    setLoading(true);
    orderApi
      .list(afterId)
      .then((r) => {
        if (!active) return;
        setOrders((prev) => (afterId === null ? r.items : [...prev, ...r.items]));
        setNextAfterId(r.hasNext ? r.nextAfterId : null);
      })
      .catch((e) => active && setError(e))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [afterId]);

  return (
    <section>
      <h1>주문</h1>
      <ErrorMessage error={error} />
      {!loading && orders.length === 0 && <p className="notice">주문이 없습니다.</p>}
      <ul className="list">
        {orders.map((o) => (
          <li key={o.id} className="card">
            <PrefetchLink to={`/orders/${o.id}`} page="orderDetail">
              #{o.id} {o.storeName}
            </PrefetchLink>{' '}
            <StatusBadge label={orderStatusLabel[o.status]} /> <Money value={o.totalPrice} />
            <div className="muted">{formatDateTime(o.createdAt)}</div>
          </li>
        ))}
      </ul>
      {loading && <DelayedSpinner />}
      {nextAfterId !== null && !loading && (
        <button type="button" className="btn btn--ghost" onClick={() => setAfterId(nextAfterId)}>
          더 보기
        </button>
      )}
    </section>
  );
}
