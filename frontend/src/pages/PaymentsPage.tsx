import { useState } from 'react';
import { paymentApi } from '../api/endpoints';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatDateTime, Money, paymentStatusLabel, StatusBadge } from '../components/format';
import { Pager } from '../components/Pager';
import { PrefetchLink } from '../components/PrefetchLink';
import { useAsync } from '../hooks/useAsync';

export default function PaymentsPage() {
  const [page, setPage] = useState(0);
  const { data, error, loading } = useAsync(() => paymentApi.list(page), [page]);

  return (
    <section>
      <h1>결제 내역</h1>
      <ErrorMessage error={error} />
      {loading && <DelayedSpinner />}
      {data && (
        <>
          {data.items.length === 0 && <p className="notice">결제 내역이 없습니다.</p>}
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>주문</th>
                  <th>상태</th>
                  <th className="amount">금액</th>
                  <th>결제 시각</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <PrefetchLink to={`/orders/${p.orderId}`} page="orderDetail">
                        #{p.orderId}
                      </PrefetchLink>
                    </td>
                    <td><StatusBadge label={paymentStatusLabel[p.status]} /></td>
                    <td className="amount"><Money value={p.amount} /></td>
                    <td>{formatDateTime(p.paidAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager page={page} hasNext={data.hasNext} onChange={setPage} />
        </>
      )}
    </section>
  );
}
