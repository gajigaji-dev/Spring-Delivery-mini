import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { useAuth } from './AuthContext';

/** 화면 접근 제어는 편의용 — 최종 권한 판단은 서버(403)가 한다 */
export function RequireRole({ roles, children }: { roles?: Role[]; children: ReactNode }) {
  const { status, me } = useAuth();
  const location = useLocation();

  if (status === 'loading') return <DelayedSpinner />;
  if (status === 'anonymous') return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (roles && me && !roles.includes(me.role)) {
    return <p className="notice">이 화면은 {roles.join(' · ')} 계정만 사용할 수 있습니다.</p>;
  }
  return <>{children}</>;
}
