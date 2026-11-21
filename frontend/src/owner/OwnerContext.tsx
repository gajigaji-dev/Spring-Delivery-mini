import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { orderApi, storeApi } from '../api/endpoints';
import type { Order, OwnerOrderEvent, Store } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { won } from '../components/format';
import { useToast } from '../components/Toast';
import type { StreamStatus } from './orderStreamController';
import { disableSound, enableSound, playNewOrderSound, soundSupported } from './sound';
import { useOrderStream } from './useOrderStream';

/*
 * 사장님 화면 공통 상태: 내 매장 목록·선택 매장·주문 목록·주문 상태 변경
 * 헤더(매장 전환·주문 배지)와 홈·주문 화면이 같은 데이터를 쓰므로 한 곳에서 받는다
 */

// 주문 목록 API는 id 오름차순만 지원 → 최신 주문을 보려면 끝까지 받아야 함.
// keyset(afterId)이라 페이지가 늘어도 각 요청 시간은 일정. 상한을 넘으면 화면에 알림 (백엔드 후보: 최신순·기간 필터)
const PAGE_SIZE = 100;
const MAX_PAGES = 50;
const STORE_KEY = 'owner:selectedStoreId';
// 실시간 알림이 짧은 간격으로 여러 건 오면 목록 조회(전체 keyset)를 한 번으로 모음
const REFRESH_COALESCE_MS = 400;

type Transition = 'ORDER_ACCEPTED' | 'DELIVERY_COMPLETED';

interface OwnerValue {
  stores: Store[];
  store: Store | null;
  selectStore: (id: number) => void;
  /** 선택 매장의 주문, 최신순 */
  orders: Order[];
  loading: boolean;
  error: unknown;
  truncated: boolean;
  loadedAt: Date | null;
  /** 성공하면 true — 실패는 error로 화면에 표시 */
  reload: () => Promise<boolean>;
  busyId: number | null;
  changeStatus: (order: Order, next: Transition) => Promise<void>;
  waitCount: number;
  /** 실시간 주문 알림 연결 상태 (#76) — 사장님이 아니면 null */
  streamStatus: StreamStatus | null;
  soundOn: boolean;
  /** 클릭 핸들러에서만 호출 (브라우저 자동 재생 정책) */
  toggleSound: () => Promise<void>;
  soundAvailable: boolean;
}

const OwnerContext = createContext<OwnerValue | null>(null);

function readSavedStore(): number | null {
  try {
    const v = localStorage.getItem(STORE_KEY);
    return v ? Number(v) : null;
  } catch {
    return null;
  }
}

function saveStore(id: number): void {
  try {
    localStorage.setItem(STORE_KEY, String(id));
  } catch {
    // 저장소를 못 쓰는 환경 — 다음 방문 때 첫 매장으로 시작할 뿐
  }
}

async function fetchAllOrders(): Promise<{ orders: Order[]; truncated: boolean }> {
  const all: Order[] = [];
  let afterId: number | null = null;
  for (let i = 0; i < MAX_PAGES; i++) {
    const page = await orderApi.list(afterId, PAGE_SIZE);
    all.push(...page.items);
    if (!page.hasNext || page.nextAfterId === null) return { orders: all, truncated: false };
    afterId = page.nextAfterId;
  }
  return { orders: all, truncated: true };
}

export function OwnerProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth();
  const { notify, notifyError } = useToast();
  const isOwner = me?.role === 'OWNER';
  const storeKey = isOwner ? me.storeIds.join(',') : '';

  const [stores, setStores] = useState<Store[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(readSavedStore);
  const [allOrders, setAllOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [truncated, setTruncated] = useState(false);
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const latest = useRef(0);

  const reload = useCallback(async () => {
    if (!isOwner) return false;
    // 늦게 도착한 이전 요청이 최신 결과를 덮지 않도록 요청 번호로 구분
    const seq = ++latest.current;
    setLoading(true);
    setError(null);
    try {
      const ids = storeKey ? storeKey.split(',').map(Number) : [];
      const [storeList, result] = await Promise.all([Promise.all(ids.map((id) => storeApi.get(id))), fetchAllOrders()]);
      if (seq !== latest.current) return false;
      setStores(storeList);
      setAllOrders(result.orders);
      setTruncated(result.truncated);
      setLoadedAt(new Date());
      return true;
    } catch (e) {
      if (seq === latest.current) setError(e);
      return false;
    } finally {
      if (seq === latest.current) setLoading(false);
    }
  }, [isOwner, storeKey]);

  useEffect(() => {
    if (!isOwner) {
      setStores([]);
      setAllOrders([]);
      return;
    }
    void reload();
  }, [isOwner, reload]);

  // ---- 실시간 주문 알림 (#76) ----
  // 화면 데이터는 항상 목록 API 결과 — 이벤트는 "다시 받을 때"라는 신호와 토스트 문구로만 씀
  const refreshTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const scheduleRefresh = useCallback(() => {
    clearTimeout(refreshTimer.current);
    refreshTimer.current = setTimeout(() => void reload(), REFRESH_COALESCE_MS);
  }, [reload]);
  useEffect(() => () => clearTimeout(refreshTimer.current), []);

  const storesRef = useRef(stores);
  storesRef.current = stores;
  const onStreamEvent = useCallback(
    (e: OwnerOrderEvent) => {
      scheduleRefresh();
      const storeName = storesRef.current.find((s) => s.id === e.storeId)?.name;
      const where = storeName ? ` · ${storeName}` : '';
      if (e.type === 'PAYMENT_COMPLETED') {
        notify(`새 주문 #${e.orderId}${where} · ${won(e.amount)}`);
        playNewOrderSound();
      } else {
        notify(`주문 #${e.orderId}${where} 고객 취소 · ${won(e.amount)} 환불`);
      }
    },
    [scheduleRefresh, notify],
  );
  const streamStatus = useOrderStream(isOwner ? me.id : null, { onEvent: onStreamEvent, onRefresh: scheduleRefresh });

  const [soundOn, setSoundOn] = useState(false);
  const toggleSound = useCallback(async () => {
    if (soundOn) {
      disableSound();
      setSoundOn(false);
      return;
    }
    const ok = await enableSound();
    setSoundOn(ok);
    notify(ok ? '새 주문이 오면 소리로 알려 드려요' : '브라우저가 소리를 막았어요. 사이트 소리 설정을 확인해 주세요', ok ? 'ok' : 'error');
  }, [soundOn, notify]);
  const soundAvailable = useMemo(soundSupported, []);

  const store = stores.find((s) => s.id === selectedId) ?? stores[0] ?? null;

  const selectStore = useCallback(
    (id: number) => {
      setSelectedId(id);
      saveStore(id);
      const picked = stores.find((s) => s.id === id);
      if (picked) notify(`${picked.name}(으)로 전환했어요`);
    },
    [stores, notify],
  );

  const orders = useMemo(
    () => (store ? allOrders.filter((o) => o.storeId === store.id).sort((a, b) => b.id - a.id) : []),
    [allOrders, store],
  );

  const changeStatus = useCallback(
    async (order: Order, next: Transition) => {
      if (busyId !== null) return;
      setBusyId(order.id);
      try {
        await orderApi.changeStatus(order.id, next);
        // 응답 본문이 없어(204) 성공한 전이만 화면에 반영 — 실제 값은 다음 새로고침 때 서버 기준으로 맞춰짐
        setAllOrders((list) => list.map((o) => (o.id === order.id ? { ...o, status: next } : o)));
        notify(`${next === 'ORDER_ACCEPTED' ? '주문을 수락했어요' : '배달 완료로 처리했어요'} · #${order.id}`);
      } catch (e) {
        notifyError(e);
      } finally {
        setBusyId(null);
      }
    },
    [busyId, notify, notifyError],
  );

  const waitCount = useMemo(() => orders.filter((o) => o.status === 'PAYMENT_COMPLETED').length, [orders]);

  const value = useMemo(
    () => ({
      stores,
      store,
      selectStore,
      orders,
      loading,
      error,
      truncated,
      loadedAt,
      reload,
      busyId,
      changeStatus,
      waitCount,
      streamStatus,
      soundOn,
      toggleSound,
      soundAvailable,
    }),
    [
      stores,
      store,
      selectStore,
      orders,
      loading,
      error,
      truncated,
      loadedAt,
      reload,
      busyId,
      changeStatus,
      waitCount,
      streamStatus,
      soundOn,
      toggleSound,
      soundAvailable,
    ],
  );
  return <OwnerContext.Provider value={value}>{children}</OwnerContext.Provider>;
}

export function useOwner(): OwnerValue {
  const ctx = useContext(OwnerContext);
  if (!ctx) throw new Error('OwnerProvider 밖에서 useOwner를 사용했습니다');
  return ctx;
}
