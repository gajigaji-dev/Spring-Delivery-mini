import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import type { Order } from '../../api/types';
import { DelayedSpinner } from '../../components/DelayedSpinner';
import { ErrorMessage } from '../../components/ErrorMessage';
import { won } from '../../components/format';
import { IconArrowRight, IconCheck } from '../../components/Icons';
import { PrefetchLink } from '../../components/PrefetchLink';
import {
  agoLabel,
  dailySales,
  dateLabel,
  feeRateLabel,
  formatPaidDate,
  itemsLabel,
  minutesSince,
  seoulDate,
  seoulHour,
  seoulTime,
  shortDate,
  sumSettlements,
  todayStats,
  trendVsAverage,
} from '../../owner/derive';
import { useOwner } from '../../owner/OwnerContext';
import { useStoreSettlements } from '../../owner/useStoreSettlements';
import { LiveStatus, NoStore, TruncatedNotice } from './OwnerCommon';

const SETTLE_DAYS = 14;

function greeting(hour: number): string {
  if (hour < 11) return '좋은 아침이에요';
  if (hour < 18) return '좋은 오후예요';
  return '좋은 저녁이에요';
}

export default function OwnerHomePage() {
  const { store, stores, orders, loading, error, truncated, loadedAt, busyId, changeStatus } = useOwner();
  const now = loadedAt ?? new Date();
  const today = seoulDate(now);

  const stats = useMemo(() => todayStats(orders, today), [orders, today]);
  // 처리 대기열: 오래 기다린 주문부터 (목록은 최신순이라 뒤집음)
  const waitList = useMemo(() => orders.filter((o) => o.status === 'PAYMENT_COMPLETED').reverse(), [orders]);
  const accList = useMemo(() => orders.filter((o) => o.status === 'ORDER_ACCEPTED').reverse(), [orders]);
  const bars = useMemo(() => dailySales(orders, today), [orders, today]);
  const trend = trendVsAverage(bars);

  if (!loading && !error && stores.length === 0) return <NoStore />;
  if (loading && !loadedAt) return <DelayedSpinner />;

  const oldest = waitList[0];
  const flowTotal = stats.wait + stats.accepted + stats.done + stats.cancelled;

  return (
    <div className="page">
      <div className="ph">
        <div>
          <span className="eyebrow num">
            {dateLabel(today)} · {seoulTime(now)} 기준
          </span>
          <h1>{greeting(seoulHour(now))}, 사장님</h1>
        </div>
        <div className="ph__actions">
          <LiveStatus />
          {waitList.length > 0 && (
            <PrefetchLink to="/owner/orders" page="ownerOrders" className="btn btn--lg">
              대기 주문 {waitList.length}건 처리하기 <IconArrowRight />
            </PrefetchLink>
          )}
        </div>
      </div>

      <ErrorMessage error={error} />
      {truncated && <TruncatedNotice />}

      <section className="hero" aria-label="오늘 요약">
        <div className="h-sales">
          <span className="eyebrow">오늘 매출 · 결제 기준, 취소 제외</span>
          <div className="mega num">
            <span className="cur">₩</span>
            {stats.sales.toLocaleString('ko-KR')}
          </div>
          <div className="facts">
            <span>
              주문 <b className="num">{stats.count}</b>건
            </span>
            <span>
              객단가 <b className="num">{stats.avgTicket === null ? '—' : won(stats.avgTicket)}</b>
            </span>
            <span>
              취소 <b className="num">{stats.cancelled}</b>건
            </span>
          </div>
        </div>
        <div className="h-wait">
          <div className="h-wait__head">
            <span>수락 대기</span>
            {oldest && (
              <span className="num">
                가장 오래된 주문 {oldest.createdAt.slice(11, 16)} · {agoLabel(minutesSince(oldest.createdAt, now))}
              </span>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span className="mega num">{waitList.length}</span>
            <span style={{ fontSize: 18, fontWeight: 800 }}>건</span>
          </div>
          <PrefetchLink to="/owner/orders" page="ownerOrders" className="btn btn--ink" style={{ alignSelf: 'flex-start' }}>
            주문 관리로 이동
          </PrefetchLink>
        </div>
        <div className="h-flow">
          <span className="eyebrow">오늘 주문 흐름</span>
          <div className="flowbar" role="img" aria-label="오늘 주문 상태 비율">
            {flowTotal > 0 && (
              <>
                <i style={{ flex: stats.wait, background: 'var(--tomato)' }} />
                <i style={{ flex: stats.accepted, background: 'var(--prog-fg)' }} />
                <i style={{ flex: stats.done, background: 'var(--done-bar)' }} />
                <i style={{ flex: stats.cancelled, background: 'var(--mute-bar)' }} />
              </>
            )}
          </div>
          <div className="fl-row">
            <div>
              <b className="num fg-wait">{stats.wait}</b>
              <span>대기</span>
            </div>
            <div>
              <b className="num fg-prog">{stats.accepted}</b>
              <span>배달 중</span>
            </div>
            <div>
              <b className="num fg-done">{stats.done}</b>
              <span>완료</span>
            </div>
            <div>
              <b className="num fg-mute">{stats.cancelled}</b>
              <span>취소</span>
            </div>
          </div>
        </div>
      </section>

      <div className="main2">
        <section>
          <div className="sec-h">
            <h2>
              지금 처리할 주문 <span className="cnt num">{waitList.length + accList.length}</span>
            </h2>
            <PrefetchLink to="/owner/orders" page="ownerOrders" className="link">
              전체 주문 보기 →
            </PrefetchLink>
          </div>
          <div className="tlist">
            {waitList.map((o) => (
              <Ticket
                key={o.id}
                order={o}
                now={now}
                busy={busyId === o.id}
                disabled={busyId !== null}
                onAct={() => changeStatus(o, 'ORDER_ACCEPTED')}
              />
            ))}
            {accList.map((o) => (
              <Ticket
                key={o.id}
                order={o}
                now={now}
                busy={busyId === o.id}
                disabled={busyId !== null}
                onAct={() => changeStatus(o, 'DELIVERY_COMPLETED')}
              />
            ))}
            {waitList.length + accList.length === 0 && (
              <div className="surface empty">
                <div className="empty__icon">
                  <IconCheck />
                </div>
                <b>모든 주문을 처리했어요</b>
                <span>새 주문이 들어오면 여기에 표시됩니다.</span>
              </div>
            )}
          </div>
        </section>

        <aside className="aside">
          {store && <SettlementPanel storeId={store.id} />}
          <div className="surface chart-card">
            <div className="chart-card__head">
              <b>최근 14일 매출</b>
              <span className="num muted" style={{ fontSize: 12.5, fontWeight: 700 }}>
                {shortDate(bars[0].date)} – 오늘
              </span>
            </div>
            <p>
              {trend.diffPct === null ? (
                '비교할 이전 매출이 아직 없어요.'
              ) : (
                <>
                  어제 매출은 14일 평균보다{' '}
                  <b className={trend.diffPct >= 0 ? 'num' : 'num fee'}>
                    {trend.diffPct >= 0 ? `${trend.diffPct}% 높아요` : `${Math.abs(trend.diffPct)}% 낮아요`}
                  </b>
                </>
              )}
            </p>
            <SalesChart bars={bars} avg={trend.avg} />
            <div className="chart-card__foot">
              <span>막대에 올리면 금액</span>
              <span>오늘은 진행 중</span>
            </div>
          </div>
        </aside>
      </div>
    </div>
  );
}

function Ticket({
  order,
  now,
  busy,
  disabled,
  onAct,
}: {
  order: Order;
  now: Date;
  busy: boolean;
  disabled: boolean;
  onAct: () => void;
}) {
  const waiting = order.status === 'PAYMENT_COMPLETED';
  return (
    <div className={`ticket${waiting ? ' ticket--wait' : ''}`}>
      <div className="t-time">
        <b className="num">{order.createdAt.slice(11, 16)}</b>
        <span className={waiting ? 'fg-wait' : 'fg-prog'}>
          {waiting ? agoLabel(minutesSince(order.createdAt, now)) : '배달 중'}
        </span>
      </div>
      <div className="t-body">
        <Link to={`/orders/${order.id}`} className="id num">
          #{order.id}
        </Link>
        <span className="it">{itemsLabel(order)}</span>
      </div>
      <div className="t-amt num">{won(order.totalPrice)}</div>
      <div className="t-act">
        <button type="button" className={`btn${waiting ? '' : ' btn--ink'}`} onClick={onAct} disabled={disabled}>
          {busy ? '처리 중…' : waiting ? '주문 수락' : '배달 완료'}
        </button>
      </div>
    </div>
  );
}

function SettlementPanel({ storeId }: { storeId: number }) {
  const { data: rows, error } = useStoreSettlements(storeId, SETTLE_DAYS);
  if (error) return <ErrorMessage error={error} />;
  const list = rows ?? [];
  const pending = list.filter((r) => r.status === 'PENDING');
  const confirmed = list.filter((r) => r.status === 'CONFIRMED');
  const lastPaid = list.find((r) => r.status === 'PAID');
  const p = sumSettlements(pending);
  const all = sumSettlements(list);
  const rate = feeRateLabel(all.sales, all.fee);

  return (
    <div className="dark">
      <div className="dark__head">
        <span className="dark__lab">정산 예정액</span>
        <span className="dark__sub">최근 {SETTLE_DAYS}일</span>
      </div>
      <div className="dark__amount num">
        <span className="cur">₩</span>
        {p.net.toLocaleString('ko-KR')}
      </div>
      <div className="dark__sub">확정 전 {pending.length}일분 · 수수료 차감 후</div>
      <div className="perf-d" style={{ marginTop: 18 }} />
      <div className="dark__row">
        <span>매출액</span>
        <b className="num">{won(p.sales)}</b>
      </div>
      <div className="dark__row">
        <span>수수료{rate && ` ${rate}`}</span>
        <b className="num fee">−{won(p.fee)}</b>
      </div>
      <div className="perf-d" />
      <div className="dark__row">
        <span>다음 입금 · 확정분</span>
        <b className="num">{won(sumSettlements(confirmed).net)}</b>
      </div>
      <div className="dark__row">
        <span>최근 입금{lastPaid?.paidAt && ` ${formatPaidDate(lastPaid.paidAt)}`}</span>
        <b className="num ok">{lastPaid ? won(lastPaid.settlementAmount) : '—'}</b>
      </div>
      <PrefetchLink to="/owner/settlements" page="ownerSettlements" className="btn btn--white btn--block" style={{ marginTop: 14 }}>
        정산 내역 보기
      </PrefetchLink>
    </div>
  );
}

function SalesChart({ bars, avg }: { bars: ReturnType<typeof dailySales>; avg: number }) {
  const max = Math.max(...bars.map((b) => b.sales), 1);
  return (
    <div className="chart" role="img" aria-label="최근 14일 일별 매출 막대 차트, 점선은 오늘 제외 평균">
      {bars.map((b) => (
        <div
          key={b.date}
          className={`bar${b.today ? ' bar--today' : b.yesterday ? ' bar--yday' : ''}`}
          title={`${shortDate(b.date)}${b.today ? ' (진행 중)' : ''} · ${won(b.sales)}`}
        >
          <i style={{ height: `${Math.max(4, Math.round((b.sales / max) * 100))}%` }} />
        </div>
      ))}
      {avg > 0 && (
        <div className="avg" style={{ bottom: `${Math.round((avg / max) * 100)}%` }}>
          <span className="num">평균 {won(avg)}</span>
        </div>
      )}
    </div>
  );
}
