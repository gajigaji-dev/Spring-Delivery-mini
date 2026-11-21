import { useState } from 'react';
import { adminApi } from '../../api/endpoints';
import type { OutboxStatus } from '../../api/types';
import { DelayedSpinner } from '../../components/DelayedSpinner';
import { ErrorMessage } from '../../components/ErrorMessage';
import { formatDateTime } from '../../components/format';
import { Pager } from '../../components/Pager';
import { useAsync } from '../../hooks/useAsync';

export default function AdminOutboxPage() {
  const [status, setStatus] = useState<OutboxStatus>('FAILED');
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useAsync(() => adminApi.outboxEvents(status, page), [status, page]);
  const [actionError, setActionError] = useState<unknown>(null);

  const republish = async (id: number) => {
    setActionError(null);
    try {
      // FAILED → PENDING으로만 되돌림, 실제 발행은 다음 Publisher 주기(1초)
      await adminApi.republish(id);
      reload();
    } catch (err) {
      setActionError(err);
    }
  };

  return (
    <section>
      <h1>Outbox 이벤트</h1>
      <select
        value={status}
        onChange={(e) => {
          setStatus(e.target.value as OutboxStatus);
          setPage(0);
        }}
      >
        <option value="FAILED">FAILED</option>
        <option value="PENDING">PENDING</option>
        <option value="PUBLISHED">PUBLISHED</option>
      </select>
      <ErrorMessage error={error ?? actionError} />
      {loading && <DelayedSpinner />}
      {data && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>id</th>
                  <th>종류</th>
                  <th>대상</th>
                  <th className="amount">시도</th>
                  <th>마지막 오류</th>
                  <th>생성</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.items.map((e) => (
                  <tr key={e.id}>
                    <td className="num">{e.id}</td>
                    <td>{e.eventType}</td>
                    <td className="num">
                      {e.aggregateType} #{e.aggregateId}
                    </td>
                    <td className="amount num">{e.attemptCount}</td>
                    <td title={e.lastError ?? ''}>{e.lastError ? e.lastError.slice(0, 40) : '-'}</td>
                    <td>{formatDateTime(e.createdAt)}</td>
                    <td>
                      {e.status === 'FAILED' && (
                        <button type="button" className="btn btn--ghost" onClick={() => republish(e.id)}>
                          재발행
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.items.length === 0 && <p className="notice">이벤트가 없습니다.</p>}
          <Pager page={page} hasNext={data.hasNext} onChange={setPage} />
        </>
      )}
    </section>
  );
}
