import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { refreshAccessToken } from '../api/client';
import { ownerApplicationApi } from '../api/endpoints';
import type { OwnerApplication } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatDateTime, StatusBadge } from '../components/format';
import { useAsync } from '../hooks/useAsync';

const roleLabel = { CUSTOMER: '고객', OWNER: '사장님', ADMIN: '관리자' } as const;

export default function MePage() {
  const { me } = useAuth();
  if (!me) return null;
  return (
    <section>
      <h1>내 정보</h1>
      <dl className="card">
        <dt className="muted">아이디</dt>
        <dd>{me.username}</dd>
        <dt className="muted">유형</dt>
        <dd>{roleLabel[me.role]}</dd>
        <dt className="muted">가입</dt>
        <dd>{formatDateTime(me.createdAt)}</dd>
        {me.role === 'OWNER' && (
          <>
            <dt className="muted">매장 id</dt>
            <dd className="num">{me.storeIds.length ? me.storeIds.join(', ') : '없음'}</dd>
          </>
        )}
      </dl>
      {me.role === 'CUSTOMER' && <OwnerApplicationSection />}
    </section>
  );
}

/** 고객 → 사장님 전환 신청 (V17 승인제). 승인되면 토큰을 재발급받아 사장님 화면으로 */
function OwnerApplicationSection() {
  const { reloadMe } = useAuth();
  const navigate = useNavigate();
  const { data: latest, error, loading, reload } = useAsync(() => ownerApplicationApi.mine(), []);
  const [businessName, setBusinessName] = useState('');
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const onApply = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setActionError(null);
    try {
      await ownerApplicationApi.apply(businessName);
      setBusinessName('');
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  // 승인 뒤에도 지금 Access Token의 role은 CUSTOMER → 재발급해야 사장님 API가 허용됨 (서버는 DB 역할로 새 토큰 발급)
  const onSwitch = async () => {
    setBusy(true);
    setActionError(null);
    try {
      await refreshAccessToken();
      await reloadMe();
      navigate('/owner');
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  if (loading && latest === undefined) return <DelayedSpinner />;

  return (
    <>
      <h2>사장님 전환</h2>
      <ErrorMessage error={error ?? actionError} />
      {latest && (
        <div className="card">
          <p>
            {latest.businessName}{' '}
            <StatusBadge label={statusLabel(latest)} tone={latest.status === 'REJECTED' ? 'danger' : latest.status === 'APPROVED' ? 'ok' : 'warn'} />
          </p>
          <p className="muted">신청 {formatDateTime(latest.requestedAt)}</p>
          {latest.status === 'REJECTED' && <p>거절 사유: {latest.rejectReason}</p>}
          {latest.status === 'APPROVED' && (
            <button type="button" className="btn" disabled={busy} onClick={onSwitch}>
              사장님 화면으로 전환
            </button>
          )}
          {latest.status === 'REQUESTED' && (
            <button type="button" className="btn btn--ghost" disabled={busy} onClick={reload}>
              승인 여부 다시 확인
            </button>
          )}
        </div>
      )}
      {(!latest || latest.status === 'REJECTED') && (
        <form className="form" onSubmit={onApply}>
          <p className="muted">진행 중인 주문이 없어야 신청할 수 있고, 전환 후에는 고객 주문·결제 화면을 쓸 수 없습니다.</p>
          <label>
            상호
            <input value={businessName} onChange={(e) => setBusinessName(e.target.value)} maxLength={50} required />
          </label>
          <button className="btn" disabled={busy}>
            {latest ? '다시 신청' : '사장님 전환 신청'}
          </button>
        </form>
      )}
    </>
  );
}

function statusLabel(a: OwnerApplication): string {
  return { REQUESTED: '승인 대기', APPROVED: '승인됨', REJECTED: '거절됨' }[a.status];
}
