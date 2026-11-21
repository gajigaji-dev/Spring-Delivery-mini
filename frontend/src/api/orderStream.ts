import { tokenStore } from '../auth/tokenStore';
import { ApiError, buildUrl, refreshAccessToken, toApiError } from './client';
import type { OwnerOrderEvent } from './types';

/*
 * 사장님 실시간 주문 알림 연결 (SSE, GET /api/owner/order-events, #76)
 *
 * 브라우저 EventSource 대신 fetch 스트림을 쓰는 이유:
 * EventSource는 헤더를 못 붙여 Access Token을 URL(쿼리)에 넣어야 함 → 서버·프록시 접근 로그와 브라우저 기록에 토큰이 남음.
 * fetch는 다른 API와 같이 Authorization: Bearer를 쓰고, 401의 code로 재발급 여부를 판단할 수 있음 (docs/api.md "인증 토큰")
 * 대신 재연결은 직접 함 → owner/orderStreamController.ts
 */

const STREAM_PATH = '/api/owner/order-events';

export interface SseMessage {
  event: string;
  data: string;
  id: string | null;
}

/** text/event-stream 파서 — 조각(chunk) 경계가 줄 중간이어도 다음 조각과 이어 붙여 해석 */
export class SseParser {
  private buffer = '';
  private event = '';
  private data: string[] = [];
  private id: string | null = null;

  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const out: SseMessage[] = [];
    let newline: number;
    while ((newline = this.buffer.indexOf('\n')) >= 0) {
      let line = this.buffer.slice(0, newline);
      this.buffer = this.buffer.slice(newline + 1);
      if (line.endsWith('\r')) line = line.slice(0, -1);
      this.handleLine(line, out);
    }
    return out;
  }

  private handleLine(line: string, out: SseMessage[]): void {
    if (line === '') {
      // 빈 줄 = 이벤트 끝. 데이터가 없는 이벤트는 표준대로 버림
      if (this.data.length > 0) out.push({ event: this.event || 'message', data: this.data.join('\n'), id: this.id });
      this.event = '';
      this.data = [];
      return;
    }
    if (line.startsWith(':')) return; // 주석(서버 하트비트 :ping)
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
    else if (field === 'id') this.id = value;
  }
}

export interface StreamHandlers {
  /** 서버가 connected 이벤트를 보냄 = 구독 성공 */
  onOpen: () => void;
  onEvent: (event: OwnerOrderEvent) => void;
  /** 하트비트 포함, 무엇이든 받으면 호출 — 조용히 끊긴 연결을 찾는 데 씀 */
  onActivity: () => void;
}

/** 다시 연결해도 같은 결과인 오류 (권한 없음·매장 없음·로그인 만료) → 재연결·주기 조회를 멈춤 */
export function isFatalStreamError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403 || error.status === 404);
}

async function fetchStream(signal: AbortSignal): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' };
  const token = tokenStore.get();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return fetch(buildUrl(STREAM_PATH), { headers, signal, cache: 'no-store' });
}

/**
 * 연결 1번 — 서버가 연결을 정상 종료하면 resolve, 실패하면 reject
 * Access 만료(TOKEN_EXPIRED)면 재발급 후 1번 다시 시도 (single-flight 재발급은 client.ts와 공유)
 */
export async function openOrderStream(signal: AbortSignal, handlers: StreamHandlers): Promise<void> {
  let res = await fetchStream(signal);
  if (res.status === 401) {
    const error = await toApiError(res);
    if (error.code !== 'TOKEN_EXPIRED' || !(await refreshAccessToken())) {
      tokenStore.clear(); // 다른 API와 같이 로그인 화면으로 (AuthProvider가 구독)
      throw error;
    }
    res = await fetchStream(signal);
  }
  if (!res.ok) throw await toApiError(res);
  if (!res.body) throw new Error('응답 본문을 읽을 수 없습니다.');

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  const parser = new SseParser();
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      handlers.onActivity();
      for (const message of parser.push(value)) {
        if (message.event === 'connected') handlers.onOpen();
        else if (message.event === 'order') handlers.onEvent(JSON.parse(message.data) as OwnerOrderEvent);
      }
    }
  } finally {
    reader.releaseLock();
  }
}
