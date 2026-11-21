import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { AuthShell } from '../components/AuthShell';
import { ErrorMessage } from '../components/ErrorMessage';
import { PrefetchLink } from '../components/PrefetchLink';
import { SocialLoginButtons } from '../components/SocialLoginButtons';
import { useToast } from '../components/Toast';

export default function SignupPage() {
  const navigate = useNavigate();
  const { notify } = useToast();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await authApi.signup(username, password);
      notify('가입했어요. 로그인해 주세요');
      navigate('/login');
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthShell
      topRight={
        <PrefetchLink to="/login" page="login" className="link">
          로그인
        </PrefetchLink>
      }
    >
      <div className="lpanel__head">
        <h2>회원가입</h2>
        {/* 역할은 고르지 않음 — 서버가 고객으로만 가입시키고, 사장님은 승인제 */}
        <p>사장님은 가입 후 '내 정보'에서 전환을 신청하면 관리자 승인 뒤 이용할 수 있어요.</p>
      </div>
      <form className="lform" onSubmit={onSubmit}>
        <div className="fld">
          <label htmlFor="signup-id">아이디 (4~20자)</label>
          <input
            id="signup-id"
            className="inp"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            minLength={4}
            maxLength={20}
            autoComplete="username"
            required
          />
        </div>
        <div className="fld">
          <label htmlFor="signup-pw">비밀번호 (8~72자)</label>
          <input
            id="signup-pw"
            className="inp"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            maxLength={72}
            autoComplete="new-password"
            required
          />
        </div>
        <ErrorMessage error={error} />
        <button type="submit" className="btn btn--lg btn--block" disabled={submitting}>
          {submitting ? '가입 중…' : '가입'}
        </button>
      </form>
      <SocialLoginButtons variant="signup" />
    </AuthShell>
  );
}
