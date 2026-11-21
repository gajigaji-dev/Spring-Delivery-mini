import { useMemo, useState } from 'react';
import type { Settlement, SettlementStatus } from '../../api/types';
import { DelayedSpinner } from '../../components/DelayedSpinner';
import { ErrorMessage } from '../../components/ErrorMessage';
import { formatDateTime, won } from '../../components/format';
import { IconDownload, IconReceipt, Logo } from '../../components/Icons';
import { useToast } from '../../components/Toast';
import {
  feeRateLabel,
  formatPaidDate,
  groupByWeek,
  settlementsCsv,
  sumSettlements,
} from '../../owner/derive';
import { useOwner } from '../../owner/OwnerContext';
import { useStoreSettlements } from '../../owner/useStoreSettlements';
import { NoStore } from './OwnerCommon';

const PERIODS = [7, 14, 30] as const;

// 정산 상태 → 사장님 관점 (확정 전 = 금액 미확정, 확정 = 입금 대기, 지급 = 끝남)
const CHIP: Record<SettlementStatus, [string, string]> = {
  PENDING: ['정산 예정', 'c-hold'],
  CONFIRMED: ['입금 대기', 'c-prog'],
  PAID: ['지급 완료', 'c-done'],
};
const DETAIL: Record<SettlementStatus, string> = {
  PENDING: '정산 예정 (확정 전)',
  CONFIRMED: '정산 확정 · 지급 대기',
  PAID: '지급 완료',
};

export default function OwnerSettlementsPage() {
  const { store, stores, loading: ownerLoading, error: ownerError } = useOwner();
  const { notify } = useToast();
  const [period, setPeriod] = useState<(typeof PERIODS)[number]>(14);
  const [tab, setTab] = useState<'daily' | 'weekly'>('daily');
  const [selId, setSelId] = useState<number | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const { data, error, loading, to } = useStoreSettlements(store?.id ?? null, period);
  const rows = useMemo(() => data ?? [], [data]);

  if (!ownerLoading && !ownerError && stores.length === 0) return <NoStore />;

  const all = sumSettlements(rows);
  const pending = rows.filter((r) => r.status === 'PENDING');
  const confirmed = rows.filter((r) => r.status === 'CONFIRMED');
  const paid = rows.filter((r) => r.status === 'PAID');
  const pend = sumSettlements(pending);
  const conf = sumSettlements(confirmed);
  const paidSum = sumSettlements(paid);
  const lastPaid = paid.find((r) => r.paidAt);
  const rate = feeRateLabel(all.sales, all.fee);
  const netPct = all.sales ? Math.round(((all.sales - all.fee) / all.sales) * 1000) / 10 : 0;
  const maxNet = Math.max(...rows.map((r) => r.settlementAmount), 1);
  // 선택이 없으면 가장 최근 정산일을 전표로 (데스크톱). 모바일 시트는 직접 고를 때만 열림
  const sel = rows.find((r) => r.id === selId) ?? (selId === null ? rows[0] : undefined);

  const downloadCsv = () => {
    if (!rows.length) {
      notify('내보낼 정산 내역이 없어요', 'error');
      return;
    }
    const blob = new Blob([settlementsCsv(rows)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `settlements-${store?.id}-${rows[rows.length - 1].settlementDate}_${rows[0].settlementDate}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    notify('CSV 파일을 내려받았어요');
  };

  return (
    <div className="page page--mid">
      <div className="ph">
        <div>
          <span className="eyebrow">{store?.name}</span>
          <h1>정산</h1>
        </div>
        <div className="ph__actions">
          <div className="seg" role="group" aria-label="조회 기간">
            {PERIODS.map((p) => (
              <button
                key={p}
                type="button"
                className={period === p ? 'on' : ''}
                aria-pressed={period === p}
                onClick={() => {
                  setPeriod(p);
                  setSelId(null);
                  setSheetOpen(false);
                }}
              >
                {p}일
              </button>
            ))}
          </div>
          <button type="button" className="btn btn--ghost" onClick={downloadCsv}>
            <IconDownload />
            CSV
          </button>
        </div>
      </div>

      <ErrorMessage error={error ?? ownerError} />
      {loading && !data && <DelayedSpinner />}

      <section className="s-hero">
        <div className="s-due">
          <div className="s-due__head">
            <span className="chip c-hold">정산 예정</span>
            <span className="muted" style={{ fontSize: 13, fontWeight: 600 }}>
              아직 입금되지 않은 금액
            </span>
          </div>
          <div className="mega num" style={{ fontSize: 'clamp(42px, 5vw, 60px)' }}>
            <span className="cur">₩</span>
            {(pend.net + conf.net).toLocaleString('ko-KR')}
          </div>
          <div className="pipe">
            <div>
              <span className="chip chip--sm c-hold">확정 전</span>
              <b className="num">{won(pend.net)}</b>
              <small className="num">{pending.length}일분</small>
            </div>
            <div>
              <span className="chip chip--sm c-prog">입금 대기</span>
              <b className="num">{won(conf.net)}</b>
              <small className="num">{confirmed.length}일분</small>
            </div>
          </div>
        </div>
        <div className="s-paid">
          <div className="s-paid__head">
            <span className="chip">지급 완료</span>
            <span style={{ fontSize: 13, fontWeight: 700 }} className="num">
              최근 {period}일 중 {paid.length}일
            </span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <span style={{ fontSize: 14, fontWeight: 700 }}>정산 완료액</span>
            <div className="s-paid__amount num">
              <span className="cur">₩</span>
              {paidSum.net.toLocaleString('ko-KR')}
            </div>
          </div>
          <div style={{ fontSize: 14, fontWeight: 600 }}>
            {lastPaid?.paidAt ? (
              <>
                최근 입금 <b className="num">{formatPaidDate(lastPaid.paidAt)}</b> ·{' '}
                <b className="num">{won(lastPaid.settlementAmount)}</b>
              </>
            ) : (
              '이 기간에 입금된 정산이 없어요'
            )}
          </div>
        </div>
      </section>

      <section>
        <div className="sec-h">
          <h2>정산은 이렇게 계산돼요</h2>
          <span className="muted num" style={{ fontSize: 13, fontWeight: 600 }}>
            최근 {period}일 합계
          </span>
        </div>
        <div className="calc">
          <div className="cb">
            <span>매출액</span>
            <b className="num">{won(all.sales)}</b>
          </div>
          <div className="op" aria-hidden="true">
            −
          </div>
          <div className="cb">
            <span>수수료{rate && ` ${rate}`}</span>
            <b className="num fee">{won(all.fee)}</b>
          </div>
          <div className="op eqn" aria-hidden="true">
            =
          </div>
          <div className="cb cb--net">
            <span>정산액</span>
            <b className="num">{won(all.net)}</b>
          </div>
        </div>
        {all.sales > 0 && (
          <>
            <div className="split-bar" role="img" aria-label={`매출 중 정산액 ${netPct}%, 수수료 ${rate}`}>
              <i style={{ flex: all.sales - all.fee }} />
              <i style={{ flex: all.fee }} />
            </div>
            <div className="split-legend">
              <span className="num">정산액 {netPct}%</span>
              <span className="fee num">수수료 {rate}</span>
            </div>
          </>
        )}
        {/* 정산액 = 매출 − 수수료 − 환불 차감 − 이월받은 금액 (docs/settlement-policy.md "정산 1건의 구성") */}
        {all.adjust !== 0 && (
          <p className="calc-note num">
            환불 차감·이월 {all.adjust < 0 ? `−${won(-all.adjust)}` : `+${won(all.adjust)}`}이 정산액에 반영돼 있어요.
          </p>
        )}
      </section>

      <section style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
        <div className="stabs" role="tablist">
          <button
            type="button"
            role="tab"
            className={tab === 'daily' ? 'on' : ''}
            aria-selected={tab === 'daily'}
            onClick={() => setTab('daily')}
          >
            날짜별 전표
          </button>
          <button
            type="button"
            role="tab"
            className={tab === 'weekly' ? 'on' : ''}
            aria-selected={tab === 'weekly'}
            onClick={() => {
              setTab('weekly');
              setSheetOpen(false);
            }}
          >
            주간 정산
          </button>
        </div>

        {tab === 'daily' ? (
          <div className="dgrid">
            <div className="tbox">
              <table className="t">
                <thead>
                  <tr>
                    <th>정산일</th>
                    <th className="r">주문</th>
                    <th className="r">매출액</th>
                    <th className="r">수수료</th>
                    <th className="r">정산액</th>
                    <th>상태</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} className={sel?.id === r.id ? 'sel' : ''}>
                      <td>
                        <button
                          type="button"
                          className="dbtn num"
                          onClick={() => {
                            setSelId(r.id);
                            setSheetOpen(true);
                          }}
                        >
                          {r.settlementDate.replaceAll('-', '.')}
                        </button>
                      </td>
                      <td className="r num">{r.paymentCount}</td>
                      <td className="r num">{won(r.totalAmount)}</td>
                      <td className="r num fee">−{won(r.totalFee)}</td>
                      <td className="r">
                        <div className="netcell">
                          <span className="mini hide-sm">
                            <i style={{ width: `${Math.round((r.settlementAmount / maxNet) * 100)}%` }} />
                          </span>
                          <b className="num">{won(r.settlementAmount)}</b>
                        </div>
                      </td>
                      <td>
                        <span className={`chip ${CHIP[r.status][1]}`}>{CHIP[r.status][0]}</span>
                      </td>
                    </tr>
                  ))}
                  {!loading && rows.length === 0 && (
                    <tr>
                      <td colSpan={6} className="muted" style={{ textAlign: 'center', padding: 36 }}>
                        이 기간에 정산 내역이 없어요. 정산은 다음 날 새벽에 집계돼요.
                      </td>
                    </tr>
                  )}
                </tbody>
                {rows.length > 0 && (
                  <tfoot>
                    <tr>
                      <td>합계 · {period}일</td>
                      <td className="r num">{all.count}</td>
                      <td className="r num">{won(all.sales)}</td>
                      <td className="r num fee">−{won(all.fee)}</td>
                      <td className="r num">{won(all.net)}</td>
                      <td />
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
            {sheetOpen && sel && (
              <button type="button" className="scrim" aria-label="전표 닫기" onClick={() => setSheetOpen(false)} />
            )}
            <div className={`slipwrap${sheetOpen && sel ? ' slipwrap--open' : ''}`}>
              <div className="sheet-handle">
                <span />
              </div>
              {sel ? (
                <Slip
                  row={sel}
                  rate={rate}
                  onClose={() => {
                    // 닫으면 선택 해제 → 데스크톱은 "정산일을 선택하세요", 모바일은 시트가 내려감
                    setSheetOpen(false);
                    setSelId(-1);
                  }}
                />
              ) : (
                <div className="surface empty" style={{ padding: '40px 24px' }}>
                  <div className="empty__icon empty__icon--hold">
                    <IconReceipt />
                  </div>
                  <b>정산일을 선택하세요</b>
                  <span>매출·수수료·지급 정보를 전표로 보여드려요.</span>
                </div>
              )}
            </div>
          </div>
        ) : (
          <WeeklyTable rows={rows} lastDate={to} period={period} />
        )}
      </section>
    </div>
  );
}

function Slip({ row, rate, onClose }: { row: Settlement; rate: string; onClose: () => void }) {
  return (
    <div className="slip">
      <div className="slip__head">
        <span className="slip__brand">
          <Logo size={22} />
          정산 전표
        </span>
        <button type="button" className="btn btn--soft btn--sm" onClick={onClose}>
          닫기
        </button>
      </div>
      <div className="slip__top">
        <div className="num muted" style={{ fontWeight: 700 }}>
          {row.settlementDate.replaceAll('-', '.')}
        </div>
        <div className="slip__amount num">{won(row.settlementAmount)}</div>
        <span className={`chip ${CHIP[row.status][1]}`}>{CHIP[row.status][0]}</span>
      </div>
      <div className="perf" />
      <div className="slip__row">
        <span>주문 건수</span>
        <span className="num">{row.paymentCount}건</span>
      </div>
      <div className="slip__row">
        <span>매출액</span>
        <span className="num">{won(row.totalAmount)}</span>
      </div>
      <div className="slip__row">
        <span>수수료{rate && ` ${rate}`}</span>
        <span className="num fee">−{won(row.totalFee)}</span>
      </div>
      {row.refundCount > 0 && (
        <div className="slip__row">
          <span>환불 차감 {row.refundCount}건</span>
          <span className="num fee">−{won(row.refundSettlementAmount)}</span>
        </div>
      )}
      {row.carriedInAmount > 0 && (
        <div className="slip__row">
          <span>이월받은 금액</span>
          <span className="num fee">−{won(row.carriedInAmount)}</span>
        </div>
      )}
      <div className="slip__row slip__row--total">
        <b>정산액</b>
        <b className="num">{won(row.settlementAmount)}</b>
      </div>
      {row.carriedOutAmount > 0 && (
        <div className="slip__row">
          <span>다음 정산으로 이월</span>
          <span className="num">{won(row.carriedOutAmount)}</span>
        </div>
      )}
      <div className="perf" />
      <div className="slip__row">
        <span>정산 상태</span>
        <span>{DETAIL[row.status]}</span>
      </div>
      <div className="slip__row">
        <span>확정 시각</span>
        <span className="num">{formatDateTime(row.confirmedAt)}</span>
      </div>
      <div className="slip__row">
        <span>지급 시각</span>
        <span className="num">{row.paidAt ? formatDateTime(row.paidAt) : '확정 후 지급'}</span>
      </div>
      <div className="slip__row">
        <span>정산 ID</span>
        <span className="num">#{row.id}</span>
      </div>
    </div>
  );
}

function WeeklyTable({ rows, lastDate, period }: { rows: Settlement[]; lastDate: string; period: number }) {
  const weeks = groupByWeek(rows, lastDate, period);
  return (
    <div className="tbox">
      <table className="t">
        <thead>
          <tr>
            <th>정산 기간</th>
            <th className="r">정산일 수</th>
            <th className="r">매출액</th>
            <th className="r">수수료</th>
            <th className="r">정산액</th>
            <th>진행 상태</th>
          </tr>
        </thead>
        <tbody>
          {weeks.map((w) => (
            <tr key={w.from}>
              <td className="num" style={{ fontWeight: 700 }}>
                {w.from.replaceAll('-', '.')} – {w.to.slice(5).replace('-', '.')}
              </td>
              <td className="r num">{w.rows.length}</td>
              <td className="r num">{won(w.sums.sales)}</td>
              <td className="r num fee">−{won(w.sums.fee)}</td>
              <td className="r num" style={{ fontWeight: 800 }}>
                {won(w.sums.net)}
              </td>
              <td>
                <span className={`chip c-${w.tone}`}>{w.label}</span>
              </td>
            </tr>
          ))}
          {weeks.length === 0 && (
            <tr>
              <td colSpan={6} className="muted" style={{ textAlign: 'center', padding: 36 }}>
                이 기간에 정산 내역이 없어요.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
