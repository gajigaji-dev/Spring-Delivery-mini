import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { demoAccounts } from '../auth/demoAccounts';
import { AuthShell } from '../components/AuthShell';
import { BootScreen } from '../components/BootScreen';
import { ErrorMessage } from '../components/ErrorMessage';
import { ROLE_HOME } from '../components/Layout';
import { PrefetchLink } from '../components/PrefetchLink';
import { SocialLoginButtons } from '../components/SocialLoginButtons';

export default function LoginPage() {
  const { status, me, login } = useAuth();
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: string } | null)?.from;
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === 'loading') return <BootScreen />;
  // 이미 로그인 상태면 역할 화면으로 (뒤로 가기로 로그인 화면에 돌아온 경우)
  if (status === 'authenticated' && me && !submitting) return <Navigate to={from ?? ROLE_HOME[me.role]} replace />;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      // 역할은 로그인 직후 받은 /me(DB 기준)로 판단 → 막혀 있던 화면이 있으면 그리로, 없으면 역할별 첫 화면
      const info = await login(username.trim(), password);
      navigate(from ?? ROLE_HOME[info.role], { replace: true });
    } catch (err) {
      setError(err);
      setSubmitting(false);
    }
  };

  return (
    <AuthShell
      topRight={
        <PrefetchLink to="/signup" page="signup" className="link">
          회원가입
        </PrefetchLink>
      }
    >
      <div className="lpanel__head">
        <h2>로그인</h2>
        <p>계정 역할에 맞는 화면으로 바로 이동합니다.</p>
      </div>
      <form className="lform" onSubmit={onSubmit}>
        <div className="fld">
          <label htmlFor="login-id">아이디</label>
          <input
            id="login-id"
            className="inp"
            autoComplete="username"
            placeholder="아이디를 입력하세요"
            value={username}
            onChange={(e) => {
              setUsername(e.target.value);
              setError(null);
            }}
            required
          />
        </div>
        <div className="fld">
          <label htmlFor="login-pw">비밀번호</label>
          <input
            id="login-pw"
            className="inp"
            type="password"
            autoComplete="current-password"
            placeholder="비밀번호를 입력하세요"
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
              setError(null);
            }}
            required
          />
        </div>
        <ErrorMessage error={error} />
        <button type="submit" className="btn btn--lg btn--block" style={{ marginTop: 4 }} disabled={submitting}>
          {submitting ? '확인 중…' : '로그인'}
        </button>
      </form>
      <SocialLoginButtons />
      {demoAccounts.length > 0 && (
        <div style={{ marginTop: 28, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div className="divider-label">테스트 계정으로 채우기</div>
          <div className="accs">
            {demoAccounts.map((a) => (
              <button
                key={`${a.role}:${a.username}`}
                type="button"
                className="acc"
                onClick={() => {
                  setUsername(a.username);
                  setPassword(a.password);
                  setError(null);
                }}
              >
                <b>{a.label}</b>
                <span>{a.role}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </AuthShell>
  );
}
