import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { ErrorMessage } from '../components/ErrorMessage';

export default function SignupPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  // ADMIN은 가입 불가(서버 400) → 선택지에서 제외
  const [role, setRole] = useState<'CUSTOMER' | 'OWNER'>('CUSTOMER');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await authApi.signup(username, password, role);
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
        <label>
          유형
          <select value={role} onChange={(e) => setRole(e.target.value as 'CUSTOMER' | 'OWNER')}>
            <option value="CUSTOMER">고객</option>
            <option value="OWNER">사장님</option>
          </select>
        </label>
        <ErrorMessage error={error} />
        <button className="btn" disabled={submitting}>
          가입
        </button>
      </form>
    </section>
  );
}
