import { settlementApi } from '../api/endpoints';
import type { Settlement } from '../api/types';
import { useAsync } from '../hooks/useAsync';
import { addDays, byDateDesc, seoulDate } from './derive';

const PAGE_SIZE = 100;

/**
 * 선택 매장의 최근 n일 정산 (어제까지 — 오늘 정산은 D+1 01:00 이후 집계되므로 없음)
 * 하루 1건이라 30일도 한 페이지지만, 재집계로 행이 늘 수 있어 hasNext 동안 이어 받음
 */
export function useStoreSettlements(storeId: number | null, days: number) {
  const today = seoulDate(new Date());
  const to = addDays(today, -1);
  const from = addDays(today, -days);
  const state = useAsync(async () => {
    if (storeId === null) return [] as Settlement[];
    const rows: Settlement[] = [];
    for (let page = 0; ; page++) {
      const r = await settlementApi.mine({ storeId, from, to, page, size: PAGE_SIZE });
      rows.push(...r.items);
      if (!r.hasNext) break;
    }
    return byDateDesc(rows);
  }, [storeId, from, to]);
  return { ...state, from, to };
}
