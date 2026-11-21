import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { refreshAccessToken } from '../api/client';
import { authApi } from '../api/endpoints';
import type { MyInfo } from '../api/types';
import { tokenStore } from './tokenStore';

type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

interface AuthContextValue {
  status: AuthStatus;
  me: MyInfo | null;
  /** 로그인 후 /me(DB 기준) 결과를 돌려줌 — 역할별 첫 화면 이동에 사용 */
  login: (username: string, password: string) => Promise<MyInfo>;
  logout: () => Promise<void>;
  reloadMe: () => Promise<unknown>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [me, setMe] = useState<MyInfo | null>(null);

  const loadMe = useCallback(async () => {
    // 역할은 토큰이 아니라 DB 기준 /me 응답으로 판단 (발급 뒤 역할이 바뀌었을 수 있음)
    const info = await authApi.me();
    setMe(info);
    setStatus('authenticated');
    return info;
  }, []);

  useEffect(() => {
    // 새로고침하면 메모리 토큰이 사라짐 → 시작 시 refresh 1번으로 로그인 복구
    // (StrictMode에서 effect가 두 번 돌아도 single-flight라 재발급은 1번)
    let cancelled = false;
    refreshAccessToken()
      .then(async (ok) => {
        if (ok) await loadMe();
        else setStatus('anonymous');
      })
      .catch(() => {
        if (!cancelled) setStatus('anonymous');
      });
    const unsubscribe = tokenStore.subscribe((token) => {
      if (token === null) {
        setMe(null);
        setStatus('anonymous');
      }
    });
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [loadMe]);

  const login = useCallback(
    async (username: string, password: string) => {
      const { accessToken } = await authApi.login(username, password);
      tokenStore.set(accessToken);
      return loadMe();
    },
    [loadMe],
  );

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      // 서버는 Access를 만료(최대 15분)까지 거부하지 않음 → 클라이언트에서 반드시 지움
      tokenStore.clear();
    }
  }, []);

  const value = useMemo(() => ({ status, me, login, logout, reloadMe: loadMe }), [status, me, login, logout, loadMe]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('AuthProvider 밖에서 useAuth를 사용했습니다');
  return ctx;
}
