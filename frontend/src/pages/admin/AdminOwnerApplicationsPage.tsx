import { useState } from 'react';
import { adminApi } from '../../api/endpoints';
import type { OwnerApplicationStatus } from '../../api/types';
import { DelayedSpinner } from '../../components/DelayedSpinner';
import { ErrorMessage } from '../../components/ErrorMessage';
import { formatDateTime } from '../../components/format';
import { Pager } from '../../components/Pager';
import { useAsync } from '../../hooks/useAsync';

/** 사장님 전환 신청 승인·거절 (V17). 상태 전이 판단은 서버(이미 처리된 신청 409, 진행 중 주문 409) */
export default function AdminOwnerApplicationsPage() {
  const [status, setStatus] = useState<OwnerApplicationStatus>('REQUESTED');
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useAsync(() => adminApi.ownerApplications(status, page), [status, page]);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const act = async (id: number, action: () => Promise<unknown>) => {
    setBusyId(id);
    setActionError(null);
    try {
      await action();
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusyId(null);
    }
  };

  const onReject = (id: number) => {
    // 임시 입력 방식 — 디자인 확정 시 모달로 교체
    const reason = window.prompt('거절 사유 (신청자에게 보입니다)');
    if (reason && reason.trim()) act(id, () => adminApi.rejectOwner(id, reason.trim()));
  };

  return (
    <section>
      <h1>사장님 전환 신청</h1>
      <select
        value={status}
        onChange={(e) => {
          setStatus(e.target.value as OwnerApplicationStatus);
          setPage(0);
        }}
      >
        <option value="REQUESTED">승인 대기</option>
        <option value="APPROVED">승인됨</option>
        <option value="REJECTED">거절됨</option>
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
                  <th>아이디</th>
                  <th>상호</th>
                  <th>신청</th>
                  <th>처리</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((a) => (
                  <tr key={a.id}>
                    <td className="num">{a.id}</td>
                    <td>{a.username}</td>
                    <td>{a.businessName}</td>
                    <td>{formatDateTime(a.requestedAt)}</td>
                    <td>
                      {a.status === 'REQUESTED' ? (
                        <span className="row">
                          <button type="button" className="btn" disabled={busyId === a.id} onClick={() => act(a.id, () => adminApi.approveOwner(a.id))}>
                            승인
                          </button>
                          <button type="button" className="btn btn--ghost" disabled={busyId === a.id} onClick={() => onReject(a.id)}>
                            거절
                          </button>
                        </span>
                      ) : (
                        <span className="muted">
                          {a.decidedBy} · {formatDateTime(a.decidedAt)}
                          {a.rejectReason && ` · ${a.rejectReason}`}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.items.length === 0 && <p className="notice">신청이 없습니다.</p>}
          <Pager page={page} hasNext={data.hasNext} onChange={setPage} />
        </>
      )}
    </section>
  );
}
