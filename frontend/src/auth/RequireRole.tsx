import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { BootScreen } from '../components/BootScreen';
import { IconInfo } from '../components/Icons';
import { ROLE_HOME } from '../components/Layout';
import { useAuth } from './AuthContext';

const ROLE_TITLE: Record<Role, string> = { CUSTOMER: '고객', OWNER: '사장님', ADMIN: '관리자' };

/** 화면 접근 제어는 편의용 — 최종 권한 판단은 서버(403)가 한다 */
export function RequireRole({ roles, children }: { roles?: Role[]; children: ReactNode }) {
  const { status, me } = useAuth();
  const location = useLocation();

  if (status === 'loading') return <BootScreen />;
  if (status === 'anonymous') return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (roles && me && !roles.includes(me.role)) {
    return (
      <div className="center-panel">
        <div className="lpanel">
          <div className="empty__icon empty__icon--hold">
            <IconInfo />
          </div>
          <span className="chip c-mute">{me.role}</span>
          <h1>{roles.map((r) => ROLE_TITLE[r]).join(' · ')} 계정만 쓸 수 있는 화면이에요</h1>
          <p>
            지금은 {ROLE_TITLE[me.role]} 계정으로 로그인되어 있어요. 내 역할에 맞는 화면으로 이동하세요.
          </p>
          <Link to={ROLE_HOME[me.role]} className="btn btn--ink">
            내 화면으로 이동
          </Link>
        </div>
      </div>
    );
  }
  return <>{children}</>;
}
