import type { Order, OrderStatus, Settlement } from '../api/types';

/*
 * 사장님 화면 계산 — 화면 컴포넌트와 분리한 순수 함수 (테스트 대상: derive.test.ts)
 * 날짜는 서버와 같은 Asia/Seoul 기준 'yyyy-MM-dd' 문자열로만 비교 → 브라우저 시간대가 섞이지 않음
 */

const SEOUL_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});
const SEOUL_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

export function seoulDate(now: Date): string {
  return SEOUL_DATE.format(now);
}

export function seoulTime(now: Date): string {
  return SEOUL_TIME.format(now);
}

export function seoulHour(now: Date): number {
  return Number(seoulTime(now).slice(0, 2));
}

/** 'yyyy-MM-dd'에 일수 더하기 (UTC 자정으로 계산해 시간대·서머타임 영향 없음) */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** 두 날짜 사이 일수 (b − a) */
export function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);
}

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];

/** 2026-11-21 → 2026.11.21 토요일 */
export function dateLabel(date: string): string {
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  return `${date.replaceAll('-', '.')} ${WEEKDAY[day]}요일`;
}

/** 2026-11-21 → 11.21 */
export function shortDate(date: string): string {
  return `${date.slice(5, 7)}.${date.slice(8, 10)}`;
}

/** 서버 시각(Asia/Seoul, 오프셋 없음)이 지금부터 몇 분 전인지 */
export function minutesSince(serverTime: string, now: Date): number {
  const t = Date.parse(`${serverTime.slice(0, 19)}+09:00`);
  return Math.max(0, Math.floor((now.getTime() - t) / 60_000));
}

export function agoLabel(minutes: number): string {
  if (minutes < 1) return '방금';
  if (minutes < 60) return `${minutes}분 경과`;
  const h = Math.floor(minutes / 60);
  return h < 24 ? `${h}시간 ${minutes % 60}분 경과` : `${Math.floor(h / 24)}일 경과`;
}

/** 오늘 주문은 시:분, 이전 주문은 월.일 시:분 */
export function orderTimeLabel(createdAt: string, today: string): string {
  const date = createdAt.slice(0, 10);
  const time = createdAt.slice(11, 16);
  return date === today ? time : `${shortDate(date)} ${time}`;
}

/** 아메리카노 × 2 외 1건 */
export function itemsLabel(order: Pick<Order, 'items'>): string {
  const [first, ...rest] = order.items;
  if (!first) return '-';
  return `${first.menuName} × ${first.quantity}${rest.length ? ` 외 ${rest.length}건` : ''}`;
}

/** 매출로 치는 주문: 결제됐고 취소되지 않은 것 (결제 전 주문은 매출 아님) */
export function isSale(status: OrderStatus): boolean {
  return status === 'PAYMENT_COMPLETED' || status === 'ORDER_ACCEPTED' || status === 'DELIVERY_COMPLETED';
}

export interface TodayStats {
  count: number;
  sales: number;
  avgTicket: number | null;
  wait: number;
  accepted: number;
  done: number;
  cancelled: number;
}

export function todayStats(orders: Order[], today: string): TodayStats {
  const todays = orders.filter((o) => o.createdAt.startsWith(today));
  const sales = todays.filter((o) => isSale(o.status));
  const total = sales.reduce((s, o) => s + o.totalPrice, 0);
  const count = (st: OrderStatus) => todays.filter((o) => o.status === st).length;
  return {
    count: todays.length,
    sales: total,
    avgTicket: sales.length ? Math.round(total / sales.length) : null,
    wait: count('PAYMENT_COMPLETED'),
    accepted: count('ORDER_ACCEPTED'),
    done: count('DELIVERY_COMPLETED'),
    cancelled: count('ORDER_CANCELLED'),
  };
}

export interface DayBar {
  date: string;
  sales: number;
  today: boolean;
  yesterday: boolean;
}

/** 최근 n일(오늘 포함) 일별 매출 — 주문 생성일 기준, 결제됐고 취소되지 않은 주문만 */
export function dailySales(orders: Order[], today: string, days = 14): DayBar[] {
  const byDate = new Map<string, number>();
  for (const o of orders) {
    if (!isSale(o.status)) continue;
    const d = o.createdAt.slice(0, 10);
    byDate.set(d, (byDate.get(d) ?? 0) + o.totalPrice);
  }
  return Array.from({ length: days }, (_, i) => {
    const date = addDays(today, i - days + 1);
    return { date, sales: byDate.get(date) ?? 0, today: i === days - 1, yesterday: i === days - 2 };
  });
}

/** 어제 매출이 오늘 제외 평균 대비 몇 % — 평균이 0이면 판단 불가(null) */
export function trendVsAverage(bars: DayBar[]): { avg: number; diffPct: number | null } {
  const past = bars.filter((b) => !b.today);
  const avg = past.length ? past.reduce((s, b) => s + b.sales, 0) / past.length : 0;
  const yday = bars.find((b) => b.yesterday);
  if (!avg || !yday) return { avg, diffPct: null };
  return { avg, diffPct: Math.round(((yday.sales - avg) / avg) * 100) };
}

/* ---- 정산 ---- */

export interface SettlementSums {
  sales: number;
  fee: number;
  net: number;
  count: number;
  /** 환불 차감·이월 반영분 = 지급액 − (매출 − 수수료) */
  adjust: number;
}

export function sumSettlements(rows: Settlement[]): SettlementSums {
  const sales = rows.reduce((s, r) => s + r.totalAmount, 0);
  const fee = rows.reduce((s, r) => s + r.totalFee, 0);
  const net = rows.reduce((s, r) => s + r.settlementAmount, 0);
  const count = rows.reduce((s, r) => s + r.paymentCount, 0);
  return { sales, fee, net, count, adjust: net - (sales - fee) };
}

/** 실효 수수료율 — 매장별 요율은 조회 API에 없어 기간 합계로 계산 (예: 3.3%) */
export function feeRateLabel(sales: number, fee: number): string {
  if (!sales) return '';
  return `${(Math.round((fee / sales) * 1000) / 10).toFixed(1)}%`;
}

/** 정산일 최신순 — 재집계로 id 순과 날짜 순이 다를 수 있음 (docs/api.md 가맹점 정산 내역) */
export function byDateDesc(rows: Settlement[]): Settlement[] {
  return [...rows].sort((a, b) => b.settlementDate.localeCompare(a.settlementDate) || b.id - a.id);
}

export interface WeekGroup {
  from: string;
  to: string;
  rows: Settlement[];
  sums: SettlementSums;
  label: string;
  tone: 'hold' | 'done' | 'prog';
}

/** 기준일(어제)부터 7일씩 끊어 묶음 — 정산이 없는 날은 빈칸이라 행 수와 일수가 다를 수 있음 */
export function groupByWeek(rows: Settlement[], lastDate: string, periodDays: number): WeekGroup[] {
  const groups: WeekGroup[] = [];
  for (let start = 0; start < periodDays; start += 7) {
    const to = addDays(lastDate, -start);
    const from = addDays(lastDate, -Math.min(start + 6, periodDays - 1));
    const inWeek = rows.filter((r) => r.settlementDate >= from && r.settlementDate <= to);
    if (!inWeek.length) continue;
    const anyPending = inWeek.some((r) => r.status === 'PENDING');
    const allPaid = inWeek.every((r) => r.status === 'PAID');
    groups.push({
      from,
      to,
      rows: inWeek,
      sums: sumSettlements(inWeek),
      label: anyPending ? '정산 예정 포함' : allPaid ? '지급 완료' : '입금 진행 중',
      tone: anyPending ? 'hold' : allPaid ? 'done' : 'prog',
    });
  }
  return groups;
}

const CSV_HEADER = ['정산일', '매장', '주문', '매출액', '수수료', '환불 차감', '이월받은 금액', '다음 이월', '정산액', '상태'];
const CSV_STATUS = { PENDING: '정산 예정', CONFIRMED: '입금 대기', PAID: '지급 완료' } as const;

/** 엑셀에서 한글이 깨지지 않도록 BOM을 붙인 CSV 문자열 */
export function settlementsCsv(rows: Settlement[]): string {
  const esc = (v: string | number) => {
    const s = String(v);
    return /[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
  };
  const lines = rows.map((r) =>
    [
      r.settlementDate,
      r.storeName,
      r.paymentCount,
      r.totalAmount,
      r.totalFee,
      r.refundSettlementAmount,
      r.carriedInAmount,
      r.carriedOutAmount,
      r.settlementAmount,
      CSV_STATUS[r.status],
    ]
      .map(esc)
      .join(','),
  );
  return `﻿${[CSV_HEADER.join(','), ...lines].join('\r\n')}\r\n`;
}

/** 서버 시각 → 2026.11.06 */
export function formatPaidDate(serverTime: string): string {
  return serverTime.slice(0, 10).replaceAll('-', '.');
}
