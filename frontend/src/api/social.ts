import { buildUrl, get, request } from './client';

/**
 * 소셜 로그인 (V22) — docs/api.md 소셜 로그인 장
 *
 * 로그인: 서버의 /oauth2/authorization/{provider}로 페이지 전체 이동 → 공급자 동의 → 서버 콜백이 Refresh 쿠키만 심고
 *        /oauth/callback?result=login 으로 돌려보냄 → 앱 시작 시 refresh(AuthContext)로 Access를 받음. 토큰은 URL에 없음
 * 연결: POST .../link(Bearer, 쿠키를 받아야 하므로 credentials 포함) → /oauth2/authorization/{provider}?mode=link 로 이동
 */
export type SocialProvider = 'kakao' | 'google' | (string & {});

export interface SocialAccounts {
  hasPassword: boolean;
  accounts: { provider: SocialProvider; linkedAt: string }[];
}

export const SOCIAL_LABELS: Record<string, string> = { kakao: '카카오', google: '구글' };

export function socialLabel(provider: string): string {
  return SOCIAL_LABELS[provider] ?? provider;
}

/** 공급자 동의 화면으로 가는 서버 주소 (fetch가 아니라 location 이동에 사용) */
export function socialAuthorizeUrl(provider: SocialProvider, mode?: 'link'): string {
  return buildUrl(`/oauth2/authorization/${encodeURIComponent(provider)}`, { mode });
}

export const socialApi = {
  /** 키가 설정된 공급자만 — 비어 있으면 버튼을 숨김 */
  providers: () => get<SocialProvider[]>('/api/users/social/providers', undefined, false),
  mine: () => get<SocialAccounts>('/api/users/me/social-accounts'),
  /** 연결 요청 쿠키(HttpOnly, 5분)를 받은 뒤 공급자로 이동 */
  startLink: async (provider: SocialProvider) => {
    await request(`/api/users/me/social-accounts/${encodeURIComponent(provider)}/link`, {
      method: 'POST',
      withCredentials: true,
    });
    window.location.assign(socialAuthorizeUrl(provider, 'link'));
  },
  unlink: (provider: SocialProvider) =>
    request(`/api/users/me/social-accounts/${encodeURIComponent(provider)}`, { method: 'DELETE' }),
};

export type OAuthCallbackResult =
  | { kind: 'login' }
  | { kind: 'linked'; provider: string }
  | { kind: 'error'; code: string };

/** /oauth/callback 쿼리 해석 — 모르는 값은 실패로 (서버가 보내지 않는 조합이면 직접 친 주소) */
export function parseOAuthCallback(search: string): OAuthCallbackResult {
  const params = new URLSearchParams(search);
  const error = params.get('error');
  if (error) return { kind: 'error', code: /^[A-Z0-9_]{1,64}$/.test(error) ? error : 'OAUTH2_FAILED' };
  const result = params.get('result');
  if (result === 'login') return { kind: 'login' };
  if (result === 'linked') return { kind: 'linked', provider: params.get('provider') ?? '' };
  return { kind: 'error', code: 'OAUTH2_FAILED' };
}
