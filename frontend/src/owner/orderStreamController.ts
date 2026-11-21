import type { OwnerOrderEvent } from '../api/types';
import type { StreamHandlers } from '../api/orderStream';

/*
 * 실시간 주문 알림 연결 관리 (#76) — 재연결·주기 조회 대체·끊김 감지
 * React와 분리한 이유: 타이머·실패 횟수에 따른 전환 규칙을 가짜 타이머로 테스트하기 위해 (orderStreamController.test.ts)
 *
 * 상태
 * - connecting: 연결 시도 중 (처음 또는 끊긴 직후)
 * - live: 연결됨, 새 주문이 바로 옴
 * - polling: 연속 실패 → 주기적으로 주문 목록을 다시 받음. 뒤에서 재연결은 계속 시도하고, 붙으면 live로 돌아감
 * - stopped: 다시 시도해도 같은 오류(권한·로그인) → 멈춤
 *
 * 연결이 한 번이라도 끊겼다가 다시 붙으면 onResync(목록 다시 받기) — 끊긴 동안 온 주문은 SSE로 다시 오지 않으므로
 */

export type StreamStatus = 'connecting' | 'live' | 'polling' | 'stopped';

export interface StreamControllerOptions {
  connect: (signal: AbortSignal, handlers: StreamHandlers) => Promise<void>;
  isFatal: (error: unknown) => boolean;
  onStatus: (status: StreamStatus) => void;
  onEvent: (event: OwnerOrderEvent) => void;
  /** 주기 조회 — polling 상태에서 pollIntervalMs마다(전환 직후 1번 포함) */
  onPoll: () => void;
  /** 끊겼다가 다시 연결됨 → 놓친 주문을 목록 조회로 맞춤 */
  onResync: () => void;
  /** 재연결 대기(ms) — 실패할수록 길게, 마지막 값에서 멈춤 */
  backoffMs?: number[];
  /** 연속 실패가 이 횟수에 닿으면 주기 조회로 전환 */
  failuresBeforePolling?: number;
  pollIntervalMs?: number;
  /** 이 시간 동안 아무것도 오지 않으면(서버 하트비트 25초) 끊긴 것으로 보고 다시 연결 */
  idleTimeoutMs?: number;
  /** 0~1 — 서버 재시작 때 모든 탭이 같은 순간에 재연결하지 않도록 대기 시간을 조금씩 흩뜨림 */
  random?: () => number;
}

const DEFAULT_BACKOFF = [1_000, 2_000, 5_000, 10_000, 30_000];

export class OrderStreamController {
  private readonly o: Required<StreamControllerOptions>;
  private status: StreamStatus | null = null;
  private failures = 0;
  private needResync = false;
  private stopped = true;
  private abort: AbortController | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private idleTimer: ReturnType<typeof setTimeout> | undefined;
  private pollTimer: ReturnType<typeof setInterval> | undefined;

  constructor(options: StreamControllerOptions) {
    this.o = {
      backoffMs: DEFAULT_BACKOFF,
      failuresBeforePolling: 3,
      pollIntervalMs: 30_000,
      idleTimeoutMs: 60_000,
      random: Math.random,
      ...options,
    };
  }

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.failures = 0;
    this.needResync = false;
    this.attempt();
  }

  stop(): void {
    this.stopped = true;
    this.abort?.abort();
    this.abort = null;
    clearTimeout(this.retryTimer);
    clearTimeout(this.idleTimer);
    this.stopPolling();
  }

  private get polling(): boolean {
    return this.pollTimer !== undefined;
  }

  private setStatus(status: StreamStatus): void {
    if (this.status === status) return;
    this.status = status;
    this.o.onStatus(status);
  }

  private attempt(): void {
    if (this.stopped) return;
    // 주기 조회 중이면 재연결을 시도하는 동안에도 화면에는 polling을 유지 (연결되면 live로)
    if (!this.polling) this.setStatus('connecting');
    const abort = new AbortController();
    this.abort = abort;
    let opened = false;

    this.o
      .connect(abort.signal, {
        onOpen: () => {
          if (abort !== this.abort) return;
          opened = true;
          this.failures = 0;
          this.stopPolling();
          this.setStatus('live');
          if (this.needResync) {
            this.needResync = false;
            this.o.onResync();
          }
        },
        onEvent: (event) => {
          if (abort === this.abort) this.o.onEvent(event);
        },
        onActivity: () => {
          if (abort === this.abort) this.armIdleTimer(abort);
        },
      })
      .then(
        () => this.onClosed(abort, opened, null),
        (error: unknown) => this.onClosed(abort, opened, error),
      );
    this.armIdleTimer(abort);
  }

  private onClosed(abort: AbortController, opened: boolean, error: unknown): void {
    // 내가 끊은 연결(stop·끊김 감지 후 교체)의 뒤늦은 결과는 무시
    if (this.stopped || abort !== this.abort) return;
    clearTimeout(this.idleTimer);
    this.needResync = true;

    if (error !== null && this.o.isFatal(error)) {
      this.stop();
      this.setStatus('stopped');
      return;
    }
    // 연결된 뒤 서버가 정상 종료(연결 유지 시간 만료) → 실패로 세지 않고 곧바로 다시 연결
    if (opened && error === null) {
      this.scheduleRetry(0);
      return;
    }
    this.failures += 1;
    if (this.failures >= this.o.failuresBeforePolling) this.startPolling();
    const base = this.o.backoffMs[Math.min(this.failures - 1, this.o.backoffMs.length - 1)];
    this.scheduleRetry(Math.round(base * (0.8 + 0.2 * this.o.random())));
  }

  private scheduleRetry(delayMs: number): void {
    if (!this.polling) this.setStatus('connecting');
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.attempt(), delayMs);
  }

  // 응답이 멈춘 연결(노트북 절전·네트워크 전환·프록시가 말없이 끊음)은 fetch가 오류 없이 계속 기다림 → 직접 끊고 실패로 처리
  private armIdleTimer(abort: AbortController): void {
    clearTimeout(this.idleTimer);
    this.idleTimer = setTimeout(() => {
      if (this.stopped || abort !== this.abort) return;
      const replaced = new AbortController(); // 이전 연결의 결과가 onClosed에서 무시되도록 먼저 교체
      this.abort = replaced;
      abort.abort();
      this.onClosed(replaced, false, new Error('실시간 연결 응답 없음'));
    }, this.o.idleTimeoutMs);
  }

  private startPolling(): void {
    if (this.polling) return;
    this.setStatus('polling');
    this.o.onPoll();
    this.pollTimer = setInterval(() => this.o.onPoll(), this.o.pollIntervalMs);
  }

  private stopPolling(): void {
    clearInterval(this.pollTimer);
    this.pollTimer = undefined;
  }
}
