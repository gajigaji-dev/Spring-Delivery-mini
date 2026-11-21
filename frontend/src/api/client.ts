import { tokenStore } from '../auth/tokenStore';
import type { ApiErrorBody, TokenResponse } from './types';

const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8090';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly traceId: string | null;

  constructor(status: number, message: string, code: string | null, traceId: string | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.traceId = traceId;
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | undefined | null>;
  headers?: Record<string, string>;
  /** false면 Authorization 헤더를 붙이지 않고 401 재발급도 하지 않음 (로그인·가입 등) */
  auth?: boolean;
  /** login·refresh·logout만 true — Refresh 쿠키는 Path=/api/users라 다른 API에는 필요 없음 */
  withCredentials?: boolean;
}

export interface ApiResponse<T> {
  data: T;
  headers: Headers;
}

/** 목록 응답: 본문은 배열, 페이지 정보는 헤더 (docs/api.md 공통 사항 — 페이지네이션·keyset) */
export interface PageResult<T> {
  items: T[];
  hasNext: boolean;
  nextAfterId: number | null;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(BASE_URL + path);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      // 선택 조건은 값이 있을 때만 보냄 — 빈 문자열을 보내면 서버에서 형식 오류(400)
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
    }
  }
  return url.toString();
}

async function toApiError(res: Response): Promise<ApiError> {
  let body: Partial<ApiErrorBody> = {};
  try {
    body = await res.json();
  } catch {
    // CORS 거부 403 등은 공통 오류 형식이 아님 → 상태 코드만으로 처리
  }
  return new ApiError(
    res.status,
    body.message ?? `요청에 실패했습니다 (${res.status})`,
    body.code ?? null,
    body.traceId ?? res.headers.get('X-Trace-Id'),
  );
}

// ---- Access Token 재발급 (single-flight) ----
// 같은 Refresh Token으로 두 번 재발급하면 서버가 재사용(REFRESH_TOKEN_REUSED)으로 보고 로그인 전체를 폐기함
// → 탭 안에서는 진행 중인 Promise를 공유하고, 탭 사이에서는 Web Locks로 순서를 맞춘다
let refreshing: Promise<boolean> | null = null;

async function doRefresh(): Promise<boolean> {
  const res = await fetch(buildUrl('/api/users/refresh'), { method: 'POST', credentials: 'include' });
  if (!res.ok) {
    tokenStore.clear();
    return false;
  }
  const body = (await res.json()) as TokenResponse;
  tokenStore.set(body.accessToken);
  return true;
}

export function refreshAccessToken(): Promise<boolean> {
  if (!refreshing) {
    const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
    // lib.dom 타입은 콜백의 Promise를 한 겹 더 감싸 선언함 → then으로 풀어 Promise<boolean>으로 맞춤
    const run = locks ? locks.request('delivery-mini:refresh', doRefresh).then((ok) => ok) : doRefresh();
    refreshing = run.finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

export async function request<T>(path: string, options: RequestOptions = {}, retried = false): Promise<ApiResponse<T>> {
  const { method = 'GET', body, query, headers = {}, auth = true, withCredentials = false } = options;
  const finalHeaders: Record<string, string> = { ...headers };
  if (body !== undefined) finalHeaders['Content-Type'] = 'application/json';
  const token = tokenStore.get();
  if (auth && token) finalHeaders['Authorization'] = `Bearer ${token}`;

  const res = await fetch(buildUrl(path, query), {
    method,
    headers: finalHeaders,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    credentials: withCredentials ? 'include' : 'same-origin',
  });

  if (!res.ok) {
    const error = await toApiError(res);
    if (res.status === 401 && auth) {
      // 분기는 message 문구가 아니라 code로 — TOKEN_EXPIRED만 재발급 후 1번 재시도
      if (error.code === 'TOKEN_EXPIRED' && !retried && (await refreshAccessToken())) {
        return request<T>(path, options, true);
      }
      // TOKEN_INVALID·재발급 실패 등은 로그인 화면으로 (AuthProvider가 토큰 삭제를 구독)
      tokenStore.clear();
    }
    throw error;
  }

  const data = res.status === 204 ? (undefined as T) : ((await res.json()) as T);
  return { data, headers: res.headers };
}

export async function get<T>(path: string, query?: RequestOptions['query'], auth = true): Promise<T> {
  return (await request<T>(path, { query, auth })).data;
}

export async function getPage<T>(path: string, query?: RequestOptions['query'], auth = true): Promise<PageResult<T>> {
  const { data, headers } = await request<T[]>(path, { query, auth });
  const nextAfterId = headers.get('X-Next-After-Id');
  return {
    items: data,
    hasNext: headers.get('X-Has-Next') === 'true',
    nextAfterId: nextAfterId ? Number(nextAfterId) : null,
  };
}
