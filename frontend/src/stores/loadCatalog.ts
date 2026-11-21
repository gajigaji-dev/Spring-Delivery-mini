import { menuApi, storeApi } from '../api/endpoints';
import type { PageResult } from '../api/client';
import type { Store } from '../api/types';
import { summarizeMenus, type MenuSummary } from './storeCatalog';

/**
 * 매장 목록 화면 데이터 — 매장·메뉴를 끝까지 받아 프론트에서 검색·정렬 (서버에 검색·정렬·매장별 메뉴 필터가 없음).
 * 페이지 상한을 둬 데이터가 많아져도 요청이 끝없이 늘지 않게 함 → 넘으면 complete=false로 화면에 안내.
 * 매장이 많아지면 서버 검색·정렬(`GET /api/stores?q=&sort=`)과 매장별 메뉴 요약이 필요 (frontend/README.md 매장 목록 절)
 */
const PAGE_SIZE = 100; // 서버 최대 (PageQuery.MAX_SIZE)
export const MAX_PAGES = 10; // 매장·메뉴 각각 최대 1,000건

async function loadAll<T>(fetchPage: (page: number) => Promise<PageResult<T>>): Promise<{ items: T[]; complete: boolean }> {
  const items: T[] = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const r = await fetchPage(page);
    items.push(...r.items);
    if (!r.hasNext) return { items, complete: true };
  }
  return { items, complete: false };
}

export interface CatalogData {
  stores: Store[];
  /** 매장이 상한보다 많아 일부만 받았는지 */
  storesComplete: boolean;
  /** 메뉴 요약 — 조회 실패·상한 초과면 null (일부 메뉴로 만든 요약은 틀린 수치가 되므로 쓰지 않음) */
  menus: Map<number, MenuSummary> | null;
}

export async function loadCatalogData(): Promise<CatalogData> {
  const [stores, menus] = await Promise.allSettled([
    loadAll((page) => storeApi.list(page, PAGE_SIZE)),
    loadAll((page) => menuApi.list(page, PAGE_SIZE)),
  ]);
  if (stores.status === 'rejected') throw stores.reason; // 매장을 못 받으면 화면 전체 오류(재시도)
  return {
    stores: stores.value.items,
    storesComplete: stores.value.complete,
    // 메뉴는 보조 정보 — 실패해도 매장 목록은 보여 줌
    menus: menus.status === 'fulfilled' && menus.value.complete ? summarizeMenus(menus.value.items) : null,
  };
}
