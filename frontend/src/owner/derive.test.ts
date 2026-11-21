import { describe, expect, it } from 'vitest';
import type { Order, OrderStatus, Settlement, SettlementStatus } from '../api/types';
import { parseDemoAccounts } from '../auth/demoAccounts';
import {
  addDays,
  agoLabel,
  dailySales,
  dateLabel,
  feeRateLabel,
  groupByWeek,
  itemsLabel,
  minutesSince,
  orderTimeLabel,
  seoulDate,
  settlementsCsv,
  sumSettlements,
  todayStats,
  trendVsAverage,
} from './derive';

function order(id: number, status: OrderStatus, createdAt: string, totalPrice = 10000): Order {
  return {
    id,
    storeId: 1,
    storeName: '매장',
    items: [{ menuId: 1, menuName: '아메리카노', unitPrice: 4500, quantity: 2, linePrice: 9000 }],
    totalPrice,
    deliveryAddress: '서울',
    status,
    createdAt,
    updatedAt: createdAt,
    deliveredAt: null,
  };
}

function settlement(id: number, date: string, status: SettlementStatus, over: Partial<Settlement> = {}): Settlement {
  return {
    id,
    storeId: 1,
    storeName: '매장',
    settlementDate: date,
    paymentCount: 2,
    totalAmount: 20000,
    totalFee: 660,
    salesSettlementAmount: 19340,
    refundCount: 0,
    refundAmount: 0,
    refundFee: 0,
    refundSettlementAmount: 0,
    carriedInAmount: 0,
    carriedOutAmount: 0,
    settlementAmount: 19340,
    status,
    aggregatedAt: `${date}T01:10:00`,
    confirmedAt: null,
    paidAt: null,
    ...over,
  };
}

describe('날짜 계산 (Asia/Seoul)', () => {
  it('UTC 오후 3시 이후는 서울 기준 다음 날이다', () => {
    expect(seoulDate(new Date('2026-11-20T15:30:00Z'))).toBe('2026-11-21');
    expect(seoulDate(new Date('2026-11-20T14:59:00Z'))).toBe('2026-11-20');
  });

  it('월말을 넘겨 날짜를 더하고 뺀다', () => {
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });

  it('요일을 붙인 날짜 라벨을 만든다', () => {
    expect(dateLabel('2026-11-21')).toBe('2026.11.21 토요일');
  });

  it('서버 시각(오프셋 없음)을 서울 시각으로 보고 경과 분을 센다', () => {
    const now = new Date('2026-11-21T05:40:00Z'); // 서울 14:40
    expect(minutesSince('2026-11-21T14:32:00', now)).toBe(8);
    expect(agoLabel(0)).toBe('방금');
    expect(agoLabel(75)).toBe('1시간 15분 경과');
  });

  it('오늘 주문은 시각만, 이전 주문은 날짜를 붙인다', () => {
    expect(orderTimeLabel('2026-11-21T14:32:10', '2026-11-21')).toBe('14:32');
    expect(orderTimeLabel('2026-11-20T09:05:00', '2026-11-21')).toBe('11.20 09:05');
  });
});

describe('주문 집계', () => {
  it('메뉴가 여러 개면 첫 메뉴 외 n건으로 줄인다', () => {
    const o = order(1, 'PAYMENT_COMPLETED', '2026-11-21T10:00:00');
    expect(itemsLabel(o)).toBe('아메리카노 × 2');
    o.items.push({ menuId: 2, menuName: '라떼', unitPrice: 5000, quantity: 1, linePrice: 5000 });
    expect(itemsLabel(o)).toBe('아메리카노 × 2 외 1건');
  });

  it('오늘 매출은 결제 전·취소 주문을 빼고 계산한다', () => {
    const orders = [
      order(1, 'PAYMENT_COMPLETED', '2026-11-21T10:00:00', 10000),
      order(2, 'ORDER_ACCEPTED', '2026-11-21T11:00:00', 20000),
      order(3, 'DELIVERY_COMPLETED', '2026-11-21T12:00:00', 30000),
      order(4, 'ORDER_CANCELLED', '2026-11-21T12:30:00', 40000),
      order(5, 'ORDER_REQUEST', '2026-11-21T13:00:00', 50000),
      order(6, 'DELIVERY_COMPLETED', '2026-11-20T12:00:00', 60000),
    ];
    expect(todayStats(orders, '2026-11-21')).toEqual({
      count: 5,
      sales: 60000,
      avgTicket: 20000,
      wait: 1,
      accepted: 1,
      done: 1,
      cancelled: 1,
    });
  });

  it('최근 14일 막대는 매출이 없는 날을 0으로 채우고 오늘·어제를 표시한다', () => {
    const orders = [
      order(1, 'DELIVERY_COMPLETED', '2026-11-20T12:00:00', 30000),
      order(2, 'DELIVERY_COMPLETED', '2026-11-08T12:00:00', 10000),
      order(3, 'DELIVERY_COMPLETED', '2026-11-07T12:00:00', 99999), // 범위 밖
    ];
    const bars = dailySales(orders, '2026-11-21');
    expect(bars).toHaveLength(14);
    expect(bars[0]).toMatchObject({ date: '2026-11-08', sales: 10000 });
    expect(bars[12]).toMatchObject({ date: '2026-11-20', sales: 30000, yesterday: true });
    expect(bars[13]).toMatchObject({ date: '2026-11-21', sales: 0, today: true });
    // 평균은 오늘 제외 13일: 40000 / 13
    const trend = trendVsAverage(bars);
    expect(Math.round(trend.avg)).toBe(3077);
    expect(trend.diffPct).toBe(875);
  });

  it('이전 매출이 없으면 평균 대비를 판단하지 않는다', () => {
    expect(trendVsAverage(dailySales([], '2026-11-21')).diffPct).toBeNull();
  });
});

describe('정산 계산', () => {
  it('합계와 환불·이월 조정분을 계산한다', () => {
    const rows = [
      settlement(1, '2026-11-20', 'PENDING'),
      settlement(2, '2026-11-19', 'PAID', { refundCount: 1, refundSettlementAmount: 5000, settlementAmount: 14340 }),
    ];
    expect(sumSettlements(rows)).toEqual({ sales: 40000, fee: 1320, net: 33680, count: 4, adjust: -5000 });
    expect(feeRateLabel(40000, 1320)).toBe('3.3%');
    expect(feeRateLabel(0, 0)).toBe('');
  });

  it('어제부터 7일씩 묶고 상태를 요약한다', () => {
    const rows = [
      settlement(1, '2026-11-20', 'PENDING'),
      settlement(2, '2026-11-15', 'CONFIRMED'),
      settlement(3, '2026-11-13', 'PAID'),
      settlement(4, '2026-11-10', 'PAID'),
    ];
    const weeks = groupByWeek(rows, '2026-11-20', 14);
    expect(weeks.map((w) => [w.from, w.to, w.rows.length, w.label])).toEqual([
      ['2026-11-14', '2026-11-20', 2, '정산 예정 포함'],
      ['2026-11-07', '2026-11-13', 2, '지급 완료'],
    ]);
  });

  it('CSV는 BOM과 머리글을 붙이고 쉼표가 든 값은 따옴표로 감싼다', () => {
    const csv = settlementsCsv([settlement(1, '2026-11-20', 'PAID', { storeName: '치킨, 피자' })]);
    expect(csv.startsWith('﻿정산일,매장,')).toBe(true);
    expect(csv).toContain('2026-11-20,"치킨, 피자",2,20000,660,0,0,0,19340,지급 완료');
  });
});

describe('테스트 계정 환경 변수', () => {
  it('역할:아이디:비밀번호 목록을 읽고 형식이 틀린 항목은 버린다', () => {
    expect(parseDemoAccounts('OWNER:own1:pw1, admin:adm1:pw2,broken')).toEqual([
      { role: 'OWNER', label: '사장님', username: 'own1', password: 'pw1' },
      { role: 'ADMIN', label: '관리자', username: 'adm1', password: 'pw2' },
    ]);
    expect(parseDemoAccounts(undefined)).toEqual([]);
  });
});
