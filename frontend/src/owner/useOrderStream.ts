import { useEffect, useRef, useState } from 'react';
import { isFatalStreamError, openOrderStream } from '../api/orderStream';
import type { OwnerOrderEvent } from '../api/types';
import { OrderStreamController, type StreamStatus } from './orderStreamController';

interface Callbacks {
  onEvent: (event: OwnerOrderEvent) => void;
  /** 주기 조회·재연결 후 맞추기 — 둘 다 목록을 다시 받는 일 */
  onRefresh: () => void;
}

/**
 * 사장님 실시간 주문 알림 연결 (#76) — ownerId가 있는 동안만 연결, 콜백은 최신 값을 씀(연결을 다시 만들지 않음)
 * 다른 사장님 계정으로 바뀌면 이전 계정의 연결을 닫고 새로 연결 (토큰은 연결할 때 붙이므로)
 */
export function useOrderStream(ownerId: number | null, callbacks: Callbacks): StreamStatus | null {
  const [status, setStatus] = useState<StreamStatus | null>(null);
  const latest = useRef(callbacks);
  latest.current = callbacks;

  useEffect(() => {
    if (ownerId === null) {
      setStatus(null);
      return;
    }
    const controller = new OrderStreamController({
      connect: openOrderStream,
      isFatal: isFatalStreamError,
      onStatus: setStatus,
      onEvent: (e) => latest.current.onEvent(e),
      onPoll: () => latest.current.onRefresh(),
      onResync: () => latest.current.onRefresh(),
    });
    controller.start();
    return () => controller.stop();
  }, [ownerId]);

  return status;
}
