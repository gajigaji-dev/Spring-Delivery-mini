import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../auth/tokenStore';
import { ApiError, refreshAccessToken, request } from './client';

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

const expired = () => json(401, { status: 401, code: 'TOKEN_EXPIRED', message: '만료', traceId: 't1' });

describe('API 클라이언트 인증 처리', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('navigator', {}); // Web Locks 없는 환경 → 탭 내부 single-flight만
    tokenStore.set('old-token');
  });

  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
    tokenStore.clear();
  });

  it('동시에 여러 요청이 TOKEN_EXPIRED를 받아도 재발급은 한 번만 한다', async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith('/api/users/refresh')) {
        refreshCalls++;
        expect(init?.credentials).toBe('include');
        await new Promise((r) => setTimeout(r, 10));
        return json(200, { accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 900 });
      }
      const auth = (init?.headers as Record<string, string>)['Authorization'];
      return auth === 'Bearer new-token' ? json(200, { ok: true }) : expired();
    });

    const results = await Promise.all([request('/api/a'), request('/api/b'), request('/api/c')]);

    expect(refreshCalls).toBe(1);
    expect(results.every((r) => (r.data as { ok: boolean }).ok)).toBe(true);
    expect(tokenStore.get()).toBe('new-token');
  });

  it('TOKEN_INVALID면 재발급하지 않고 토큰을 지운다', async () => {
    fetchMock.mockResolvedValue(json(401, { status: 401, code: 'TOKEN_INVALID', message: '위조', traceId: 't2' }));

    await expect(request('/api/a')).rejects.toMatchObject({ status: 401, code: 'TOKEN_INVALID' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(tokenStore.get()).toBeNull();
  });

  it('재발급이 실패하면 원래 오류를 던지고 토큰을 지운다', async () => {
    fetchMock.mockImplementation(async (input) =>
      String(input).endsWith('/refresh')
        ? json(401, { status: 401, code: 'REFRESH_TOKEN_REUSED', message: '재사용' })
        : expired(),
    );

    const error = await request('/api/a').catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('TOKEN_EXPIRED');
    expect(tokenStore.get()).toBeNull();
  });

  it('재시도한 요청이 다시 만료되면 더 재발급하지 않는다', async () => {
    fetchMock.mockImplementation(async (input) =>
      String(input).endsWith('/refresh') ? json(200, { accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 900 }) : expired(),
    );

    await expect(request('/api/a')).rejects.toMatchObject({ code: 'TOKEN_EXPIRED' });
    expect(fetchMock).toHaveBeenCalledTimes(3); // 원 요청 → 재발급 → 재시도 1번
  });

  it('재발급이 끝나면 다음 만료 때 다시 재발급할 수 있다', async () => {
    fetchMock.mockImplementation(async () => json(200, { accessToken: 'a', tokenType: 'Bearer', expiresIn: 900 }));
    await refreshAccessToken();
    await refreshAccessToken();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('목록 응답 헤더에서 다음 페이지 정보를 읽는다', async () => {
    const { getPage } = await import('./client');
    fetchMock.mockResolvedValue(json(200, [{ id: 1 }], { 'X-Has-Next': 'true', 'X-Next-After-Id': '1' }));
    const page = await getPage<{ id: number }>('/api/orders');
    expect(page).toEqual({ items: [{ id: 1 }], hasNext: true, nextAfterId: 1 });
  });
});
