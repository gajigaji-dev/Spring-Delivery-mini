import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { isFatalStreamError, SseParser, type StreamHandlers } from '../api/orderStream';
import type { OwnerOrderEvent } from '../api/types';
import { OrderStreamController, type StreamStatus } from './orderStreamController';

const EVENT: OwnerOrderEvent = {
  notificationId: 1,
  type: 'PAYMENT_COMPLETED',
  orderId: 77,
  storeId: 4,
  amount: 15000,
  message: '새 주문 #77의 결제가 완료되었습니다.',
  occurredAt: '2026-11-21T12:00:00',
};

/** connect 호출마다 서버 역할을 테스트가 직접 함 — open·event·close·fail */
interface FakeConnection {
  handlers: StreamHandlers;
  signal: AbortSignal;
  close: () => void;
  fail: (error: unknown) => void;
}

function setup() {
  const connections: FakeConnection[] = [];
  const statuses: StreamStatus[] = [];
  const onEvent = vi.fn();
  const onPoll = vi.fn();
  const onResync = vi.fn();
  const controller = new OrderStreamController({
    connect: (signal, handlers) =>
      new Promise<void>((resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
        connections.push({ handlers, signal, close: resolve, fail: reject });
      }),
    isFatal: isFatalStreamError,
    onStatus: (s) => statuses.push(s),
    onEvent,
    onPoll,
    onResync,
    backoffMs: [1_000, 2_000, 5_000],
    failuresBeforePolling: 3,
    pollIntervalMs: 30_000,
    idleTimeoutMs: 60_000,
    random: () => 1,
  });
  const last = () => connections[connections.length - 1];
  return { controller, connections, statuses, onEvent, onPoll, onResync, last };
}

/** 실패 처리는 Promise 다음 틱에서 일어남 → 타이머 진행 전에 마이크로태스크를 비움 */
async function flush(): Promise<void> {
  await vi.advanceTimersByTimeAsync(0);
}

describe('OrderStreamController', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('연결되면 live가 되고 새 주문 이벤트를 전달한다', async () => {
    const t = setup();
    t.controller.start();
    expect(t.statuses).toEqual(['connecting']);

    t.last().handlers.onOpen();
    t.last().handlers.onEvent(EVENT);

    expect(t.statuses).toEqual(['connecting', 'live']);
    expect(t.onEvent).toHaveBeenCalledWith(EVENT);
    expect(t.onResync).not.toHaveBeenCalled(); // 첫 연결은 화면이 이미 목록을 받은 상태
    t.controller.stop();
  });

  it('연속 3번 실패하면 주기 조회로 전환하고, 재연결되면 주기 조회를 멈추고 목록을 다시 받는다', async () => {
    const t = setup();
    t.controller.start();

    t.last().fail(new TypeError('Failed to fetch'));
    await flush();
    await vi.advanceTimersByTimeAsync(1_000); // 1번째 재시도
    t.last().fail(new TypeError('Failed to fetch'));
    await flush();
    await vi.advanceTimersByTimeAsync(2_000); // 2번째 재시도
    expect(t.onPoll).not.toHaveBeenCalled();
    t.last().fail(new TypeError('Failed to fetch'));
    await flush();

    expect(t.statuses.at(-1)).toBe('polling');
    expect(t.onPoll).toHaveBeenCalledTimes(1); // 전환 직후 1번
    await vi.advanceTimersByTimeAsync(30_000);
    expect(t.onPoll).toHaveBeenCalledTimes(2);

    // 뒤에서 재연결은 계속 시도 (마지막 대기 5초) → 붙으면 live
    expect(t.connections).toHaveLength(4);
    t.last().handlers.onOpen();
    expect(t.statuses.at(-1)).toBe('live');
    expect(t.onResync).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(120_000);
    expect(t.onPoll).toHaveBeenCalledTimes(2); // live가 된 뒤로는 주기 조회 없음
    t.controller.stop();
  });

  it('서버가 연결을 정상 종료하면 실패로 세지 않고 바로 다시 연결해 놓친 주문을 맞춘다', async () => {
    const t = setup();
    t.controller.start();
    t.last().handlers.onOpen();

    t.last().close(); // 서버 연결 유지 시간(10분) 만료
    await flush();
    expect(t.connections).toHaveLength(2);
    expect(t.statuses.at(-1)).toBe('connecting');

    t.last().handlers.onOpen();
    expect(t.statuses.at(-1)).toBe('live');
    expect(t.onResync).toHaveBeenCalledTimes(1);
    expect(t.onPoll).not.toHaveBeenCalled();
    t.controller.stop();
  });

  it('권한 오류(403)면 재연결·주기 조회를 하지 않고 멈춘다', async () => {
    const t = setup();
    t.controller.start();

    t.last().fail(new ApiError(403, 'OWNER만 구독할 수 있습니다.', null, null));
    await flush();
    await vi.advanceTimersByTimeAsync(120_000);

    expect(t.statuses.at(-1)).toBe('stopped');
    expect(t.connections).toHaveLength(1);
    expect(t.onPoll).not.toHaveBeenCalled();
  });

  it('하트비트가 끊긴 채 응답이 멈추면 연결을 끊고 다시 연결한다', async () => {
    const t = setup();
    t.controller.start();
    const first = t.last();
    first.handlers.onOpen();
    first.handlers.onActivity();

    await vi.advanceTimersByTimeAsync(59_000);
    first.handlers.onActivity(); // 하트비트 → 감시 시간 초기화
    await vi.advanceTimersByTimeAsync(59_000);
    expect(first.signal.aborted).toBe(false);

    await vi.advanceTimersByTimeAsync(1_000); // 마지막 수신 후 60초
    expect(first.signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(1_000); // 1번째 실패 대기
    expect(t.connections).toHaveLength(2);
    t.controller.stop();
  });

  it('stop 후에는 연결을 끊고 더 이상 재연결·주기 조회를 하지 않는다', async () => {
    const t = setup();
    t.controller.start();
    const first = t.last();
    t.controller.stop();
    await flush();
    await vi.advanceTimersByTimeAsync(300_000);

    expect(first.signal.aborted).toBe(true);
    expect(t.connections).toHaveLength(1);
    expect(t.onPoll).not.toHaveBeenCalled();
  });
});

describe('SseParser', () => {
  it('조각 경계가 줄 중간이어도 이벤트를 이어 붙여 해석하고 주석(하트비트)은 무시한다', () => {
    const parser = new SseParser();
    expect(parser.push('event:conn')).toEqual([]);
    expect(parser.push('ected\ndata:ok\n\n:ping\n\nevent:order\nid:5\ndata:{"orderId"')).toEqual([
      { event: 'connected', data: 'ok', id: null },
    ]);
    expect(parser.push(':77}\r\n\r\n')).toEqual([{ event: 'order', data: '{"orderId":77}', id: '5' }]);
  });
});
