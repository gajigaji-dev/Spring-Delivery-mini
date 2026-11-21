import { useAuth } from '../auth/AuthContext';
import { formatDateTime } from '../components/format';

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
    </section>
  );
}
