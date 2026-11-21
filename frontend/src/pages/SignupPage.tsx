import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { ErrorMessage } from '../components/ErrorMessage';

export default function SignupPage() {
  const navigate = useNavigate();
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
      navigate('/login');
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section>
      <h1>회원가입</h1>
      <form className="form" onSubmit={onSubmit}>
        <label>
          아이디 (4~20자)
          <input value={username} onChange={(e) => setUsername(e.target.value)} minLength={4} maxLength={20} required />
        </label>
        <label>
          비밀번호 (8~72자)
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            maxLength={72}
            autoComplete="new-password"
            required
          />
        </label>
        {/* 역할은 고르지 않음 — 서버가 고객으로만 가입시키고, 사장님은 승인제 */}
        <p className="muted">사장님은 가입 후 '내 정보'에서 사장님 전환을 신청하면 관리자 승인 뒤 이용할 수 있습니다.</p>
        <ErrorMessage error={error} />
        <button className="btn" disabled={submitting}>
          가입
        </button>
      </form>
    </section>
  );
}
