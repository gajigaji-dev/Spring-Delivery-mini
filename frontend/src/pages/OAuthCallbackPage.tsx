import { useMemo } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { messageForCode } from '../api/errorMessages';
import { parseOAuthCallback, socialLabel } from '../api/social';
import { useAuth } from '../auth/AuthContext';
import { AuthShell } from '../components/AuthShell';
import { BootScreen } from '../components/BootScreen';
import { ROLE_HOME } from '../components/Layout';
import { PrefetchLink } from '../components/PrefetchLink';

/**
 * 소셜 로그인·연결 결과 화면 (/oauth/callback, V22)
 * 서버 콜백에서 페이지 전체 이동으로 들어오므로 AuthContext가 시작 시 refresh를 먼저 함
 * - result=login: 서버가 심은 Refresh 쿠키로 refresh 성공 → 역할 첫 화면. 실패하면 쿠키가 막힌 경우(브라우저 설정)
 * - result=linked: 내 정보로 이동 (연결 결과가 목록에 보임)
 * - error=CODE: 안내 문구 + 돌아갈 곳
 */
export default function OAuthCallbackPage() {
  const { status, me } = useAuth();
  const search = useLocation().search;
  const result = useMemo(() => parseOAuthCallback(search), [search]);

  if (status === 'loading') return <BootScreen />;

  if (result.kind === 'login' && status === 'authenticated' && me) {
    return <Navigate to={ROLE_HOME[me.role]} replace />;
  }
  if (result.kind === 'linked' && status === 'authenticated') {
    return <Navigate to="/me" replace state={{ notice: `${socialLabel(result.provider)} 계정을 연결했습니다.` }} />;
  }

  const message =
    result.kind === 'error'
      ? messageForCode(result.code)
      : // 성공으로 돌아왔는데 로그인 상태가 아님 = refresh 쿠키를 받지 못함 (서드파티 쿠키 차단 등)
        '로그인 정보를 확인하지 못했습니다. 브라우저의 쿠키 설정을 확인한 뒤 다시 시도해 주세요.';
  const linkFlow = result.kind === 'error' && result.code.startsWith('SOCIAL_') && status === 'authenticated';

  return (
    <AuthShell topRight={null}>
      <div className="lpanel__head">
        <h2>{result.kind === 'error' && result.code === 'OAUTH2_CANCELLED' ? '로그인 취소' : '소셜 로그인'}</h2>
        <p role="alert">{message}</p>
      </div>
      {linkFlow ? (
        <PrefetchLink to="/me" page="me" className="btn btn--lg btn--block">
          내 정보로 돌아가기
        </PrefetchLink>
      ) : (
        <PrefetchLink to="/login" page="login" className="btn btn--lg btn--block">
          로그인 화면으로
        </PrefetchLink>
      )}
    </AuthShell>
  );
}
