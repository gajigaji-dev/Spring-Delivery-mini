import { useState } from 'react';
import { settlementApi } from '../../api/endpoints';
import type { SettlementStatus } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { DelayedSpinner } from '../../components/DelayedSpinner';
import { ErrorMessage } from '../../components/ErrorMessage';
import { Money, settlementStatusLabel, StatusBadge } from '../../components/format';
import { Pager } from '../../components/Pager';
import { useAsync } from '../../hooks/useAsync';

interface Filter {
  storeId?: number;
  status?: SettlementStatus;
  from?: string;
  to?: string;
}

export default function OwnerSettlementsPage() {
  const { me } = useAuth();
  const [filter, setFilter] = useState<Filter>({});
  const [page, setPage] = useState(0);
  const { data, error, loading } = useAsync(() => settlementApi.mine({ ...filter, page }), [JSON.stringify(filter), page]);

  // 조건이 바뀌면 첫 페이지부터
  const update = (patch: Filter) => {
    setFilter((f) => ({ ...f, ...patch }));
    setPage(0);
  };

  return (
    <section>
      <h1>정산 내역</h1>
      <div className="row">
        <select value={filter.storeId ?? ''} onChange={(e) => update({ storeId: e.target.value ? Number(e.target.value) : undefined })}>
          <option value="">전체 매장</option>
          {me?.storeIds.map((id) => (
            <option key={id} value={id}>
              매장 #{id}
            </option>
          ))}
        </select>
        <select
          value={filter.status ?? ''}
          onChange={(e) => update({ status: (e.target.value || undefined) as SettlementStatus | undefined })}
        >
          <option value="">전체 상태</option>
          {Object.entries(settlementStatusLabel).map(([v, label]) => (
            <option key={v} value={v}>
              {label}
            </option>
          ))}
        </select>
        <input type="date" value={filter.from ?? ''} onChange={(e) => update({ from: e.target.value || undefined })} />
        <input type="date" value={filter.to ?? ''} onChange={(e) => update({ to: e.target.value || undefined })} />
      </div>
      <ErrorMessage error={error} />
      {loading && <DelayedSpinner />}
      {data && (
        <>
          {data.items.length === 0 && <p className="notice">정산 내역이 없습니다.</p>}
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>정산일</th>
                  <th>매장</th>
                  <th>상태</th>
                  <th className="amount">판매</th>
                  <th className="amount">수수료</th>
                  <th className="amount">환불 정산</th>
                  <th className="amount">이월</th>
                  <th className="amount">지급액</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((s) => (
                  <tr key={s.id}>
                    <td className="num">{s.settlementDate}</td>
                    <td>{s.storeName}</td>
                    <td><StatusBadge label={settlementStatusLabel[s.status]} tone={s.status === 'PAID' ? 'ok' : 'neutral'} /></td>
                    <td className="amount"><Money value={s.totalAmount} /></td>
                    <td className="amount"><Money value={s.totalFee} /></td>
                    <td className="amount"><Money value={s.refundSettlementAmount} /></td>
                    <td className="amount"><Money value={s.carriedOutAmount} /></td>
                    <td className="amount"><strong><Money value={s.settlementAmount} /></strong></td>
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
