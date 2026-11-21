import { useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { refreshAccessToken } from '../api/client';
import { ownerApplicationApi } from '../api/endpoints';
import { socialApi, socialLabel } from '../api/social';
import { SocialLogo } from '../components/SocialLogo';
import type { OwnerApplication } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatDateTime, StatusBadge } from '../components/format';
import { useAsync } from '../hooks/useAsync';

const roleLabel = { CUSTOMER: '고객', OWNER: '사장님', ADMIN: '관리자' } as const;

export default function MePage() {
  const { me } = useAuth();
  // 소셜 계정 연결 후 /oauth/callback에서 넘어온 안내
  const notice = (useLocation().state as { notice?: string } | null)?.notice;
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
      {notice && <p className="muted" role="status">{notice}</p>}
      {/* 관리자는 소셜 연결 불가 (서버도 403) — 관리자 권한이 소셜 계정 보안에 의존하지 않도록 */}
      {me.role !== 'ADMIN' && <SocialAccountsSection />}
      {me.role === 'CUSTOMER' && <OwnerApplicationSection />}
    </section>
  );
}

/** 소셜 계정 연결·해제 (V22) — 연결은 로그인한 상태에서만, 공급자 화면에 다녀온 뒤 /oauth/callback → 이 화면 */
function SocialAccountsSection() {
  const { data: providers } = useAsync(() => socialApi.providers(), []);
  const { data: mine, error, reload } = useAsync(() => socialApi.mine(), []);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  // 키가 설정된 공급자 + 이미 연결된 공급자(나중에 꺼졌어도 해제는 할 수 있게)
  const all = [...new Set([...(providers ?? []), ...(mine?.accounts.map((a) => a.provider) ?? [])])];
  if (all.length === 0 && !error) return null;

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h2>소셜 계정</h2>
      <ErrorMessage error={error ?? actionError} />
      {mine && (
        <ul className="card" style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 10 }}>
          {all.map((p) => {
            const linked = mine.accounts.find((a) => a.provider === p);
            const lastMethod = linked && !mine.hasPassword && mine.accounts.length === 1;
            return (
              <li key={p} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <SocialLogo provider={p} />
                  {socialLabel(p)}{' '}
                  {linked ? (
                    <span className="muted">연결됨 · {formatDateTime(linked.linkedAt)}</span>
                  ) : (
                    <span className="muted">연결 안 됨</span>
                  )}
                </span>
                {linked ? (
                  <button
                    type="button"
                    className="btn btn--ghost"
                    disabled={busy || lastMethod}
                    title={lastMethod ? '비밀번호가 없는 계정은 마지막 소셜 계정을 해제할 수 없습니다' : undefined}
                    onClick={() => run(() => socialApi.unlink(p))}
                  >
                    해제
                  </button>
                ) : (
                  <button type="button" className="btn" disabled={busy} onClick={() => run(() => socialApi.startLink(p))}>
                    연결
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </>
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
