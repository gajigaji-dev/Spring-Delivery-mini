import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { Order, OrderStatus } from '../../api/types';
import { DelayedSpinner } from '../../components/DelayedSpinner';
import { ErrorMessage } from '../../components/ErrorMessage';
import { orderStatusLabel, won } from '../../components/format';
import { IconRefresh } from '../../components/Icons';
import { useToast } from '../../components/Toast';
import { itemsLabel, orderTimeLabel, seoulDate } from '../../owner/derive';
import { useOwner } from '../../owner/OwnerContext';
import { LiveStatus, NoStore, TruncatedNotice } from './OwnerCommon';

type Filter = 'ALL' | 'PAYMENT_COMPLETED' | 'ORDER_ACCEPTED' | 'DELIVERY_COMPLETED' | 'ORDER_CANCELLED';

// 사장님 관점 라벨 — 결제 완료 = 수락 대기 (결제 전 주문은 수락할 수 없어 '전체'에만 보임)
const FILTERS: { key: Filter; label: string; dot: string }[] = [
  { key: 'ALL', label: '전체', dot: 'var(--ink)' },
  { key: 'PAYMENT_COMPLETED', label: '수락 대기', dot: 'var(--tomato-600)' },
  { key: 'ORDER_ACCEPTED', label: '배달 중', dot: 'var(--prog-fg)' },
  { key: 'DELIVERY_COMPLETED', label: '배달 완료', dot: 'var(--done-fg)' },
  { key: 'ORDER_CANCELLED', label: '취소', dot: 'var(--text-faint)' },
];

const CHIP: Record<OrderStatus, [string, string]> = {
  ORDER_REQUEST: [orderStatusLabel.ORDER_REQUEST, 'c-hold'],
  PAYMENT_COMPLETED: ['수락 대기', 'c-wait'],
  ORDER_ACCEPTED: ['배달 중', 'c-prog'],
  DELIVERY_COMPLETED: ['배달 완료', 'c-done'],
  ORDER_CANCELLED: ['취소', 'c-mute'],
};

// 목록은 이미 전부 받아 둔 상태 → 화면에는 20건씩 늘려 보여 줌 (긴 목록 한 번에 그리지 않기)
const STEP = 20;

export default function OwnerOrdersPage() {
  const { store, stores, orders, loading, error, truncated, loadedAt, reload, busyId, changeStatus } = useOwner();
  const { notify } = useToast();
  const [filter, setFilter] = useState<Filter>('ALL');
  const [shown, setShown] = useState(STEP);

  const today = seoulDate(loadedAt ?? new Date());
  const todayCount = useMemo(() => orders.filter((o) => o.createdAt.startsWith(today)).length, [orders, today]);
  const counts = useMemo(() => {
    const c: Record<Filter, number> = { ALL: orders.length, PAYMENT_COMPLETED: 0, ORDER_ACCEPTED: 0, DELIVERY_COMPLETED: 0, ORDER_CANCELLED: 0 };
    for (const o of orders) if (o.status in c) c[o.status as Filter]++;
    return c;
  }, [orders]);
  const filtered = useMemo(() => (filter === 'ALL' ? orders : orders.filter((o) => o.status === filter)), [orders, filter]);
  const visible = filtered.slice(0, shown);

  if (!loading && !error && stores.length === 0) return <NoStore />;
  if (loading && !loadedAt) return <DelayedSpinner />;

  const onRefresh = async () => {
    if (await reload()) notify('최신 주문을 불러왔어요');
  };

  const act = (o: Order) =>
    o.status === 'PAYMENT_COMPLETED' ? changeStatus(o, 'ORDER_ACCEPTED') : changeStatus(o, 'DELIVERY_COMPLETED');

  return (
    <div className="page page--tight">
      <div className="ph">
        <div>
          <span className="eyebrow">
            {store?.name} · 오늘 <span className="num">{todayCount}</span>건
          </span>
          <h1>주문 관리</h1>
        </div>
        <div className="ph__actions">
          <LiveStatus />
          <button type="button" className="btn btn--ghost" onClick={onRefresh} disabled={loading}>
            <IconRefresh />
            {loading ? '불러오는 중…' : '새로고침'}
          </button>
        </div>
      </div>

      <ErrorMessage error={error} />
      {truncated && <TruncatedNotice />}

      <div className="fchips" role="group" aria-label="주문 상태 필터">
        {FILTERS.map((f) => {
          const on = filter === f.key;
          return (
            <button
              key={f.key}
              type="button"
              className={on ? 'on' : ''}
              aria-pressed={on}
              onClick={() => {
                setFilter(f.key);
                setShown(STEP);
              }}
            >
              <span className="fdot" style={{ background: on && f.key === 'ALL' ? 'var(--surface)' : f.dot }} />
              {f.label}
              <span className="n num">{counts[f.key]}</span>
            </button>
          );
        })}
      </div>

      <div className="olist">
        <div className="orow orow--hd">
          <span>주문</span>
          <span>메뉴</span>
          <span className="r">금액</span>
          <span>상태</span>
          <span className="r">처리</span>
        </div>
        {visible.map((o) => (
          <div key={o.id} className={`orow${o.status === 'PAYMENT_COMPLETED' ? ' orow--wait' : ''}`}>
            <div className="o-id">
              <Link to={`/orders/${o.id}`} className="num">
                #{o.id}
              </Link>
              <span className="num">{orderTimeLabel(o.createdAt, today)}</span>
            </div>
            <div className="o-it">{itemsLabel(o)}</div>
            <div className={`o-amt num${o.status === 'ORDER_CANCELLED' ? ' o-amt--x' : ''}`}>{won(o.totalPrice)}</div>
            <div>
              <span className={`chip ${CHIP[o.status][1]}`}>{CHIP[o.status][0]}</span>
            </div>
            <div className="o-act">
              <ActionButton order={o} busyId={busyId} onAct={() => act(o)} />
            </div>
          </div>
        ))}
        {filtered.length === 0 && <div className="empty">이 상태의 주문이 없어요.</div>}
      </div>

      <div className="ocards">
        {visible.map((o) => (
          <article key={o.id} className={`ocard${o.status === 'PAYMENT_COMPLETED' ? ' ocard--wait' : ''}`}>
            <div className="ocard__head">
              <span className={`chip ${CHIP[o.status][1]}`}>{CHIP[o.status][0]}</span>
              <Link to={`/orders/${o.id}`} className="num">
                {orderTimeLabel(o.createdAt, today)} · #{o.id}
              </Link>
            </div>
            <div className="ocard__items">{itemsLabel(o)}</div>
            <div className={`o-amt num${o.status === 'ORDER_CANCELLED' ? ' o-amt--x' : ''}`} style={{ textAlign: 'left', fontSize: 20 }}>
              {won(o.totalPrice)}
            </div>
            <ActionButton order={o} busyId={busyId} onAct={() => act(o)} block />
          </article>
        ))}
        {filtered.length === 0 && <div className="surface empty">이 상태의 주문이 없어요.</div>}
      </div>

      <div className="center">
        {filtered.length > shown ? (
          <button type="button" className="btn btn--soft" onClick={() => setShown((n) => n + STEP)}>
            이전 주문 더 보기
          </button>
        ) : (
          filtered.length > 0 && (
            <span className="muted" style={{ fontWeight: 600 }}>
              모든 주문을 불러왔어요
            </span>
          )
        )}
      </div>
    </div>
  );
}

function ActionButton({
  order,
  busyId,
  onAct,
  block = false,
}: {
  order: Order;
  busyId: number | null;
  onAct: () => void;
  block?: boolean;
}) {
  const busy = busyId === order.id;
  const cls = block ? ' btn--block' : '';
  if (order.status === 'PAYMENT_COMPLETED') {
    return (
      <button type="button" className={`btn${cls}`} onClick={onAct} disabled={busyId !== null}>
        {busy ? '처리 중…' : '주문 수락'}
      </button>
    );
  }
  if (order.status === 'ORDER_ACCEPTED') {
    return (
      <button type="button" className={`btn btn--ink${cls}`} onClick={onAct} disabled={busyId !== null}>
        {busy ? '처리 중…' : '배달 완료'}
      </button>
    );
  }
  return block ? null : (
    <span className="muted" aria-label="처리할 일 없음">
      —
    </span>
  );
}
