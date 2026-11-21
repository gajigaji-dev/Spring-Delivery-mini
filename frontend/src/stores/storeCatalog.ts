import type { Menu, Store } from '../api/types';

/**
 * 고객 매장 목록의 검색·필터·정렬 (순수 함수 — 테스트 대상).
 * 매장 API(`GET /api/stores`)에는 검색·정렬·카테고리가 없어 받은 목록을 프론트에서 처리한다.
 * 매장 정보는 API가 주는 값(이름·등록일)과 메뉴 API에서 계산한 값(메뉴 수·최저가·대표 메뉴)만 쓴다 —
 * 카테고리·이미지·영업 상태·평점·배달 시간은 서버에 없으므로 만들지 않음 (frontend/README.md 매장 목록 절).
 */

export interface MenuSummary {
  count: number;
  minPrice: number;
  /** 등록순 앞의 메뉴 이름 (카드 표시·검색용 전체 이름은 names) */
  names: string[];
}

export type StoreFilter = 'all' | 'orderable' | 'new';
export type StoreSort = 'default' | 'newest' | 'name' | 'menus';

export interface CatalogQuery {
  q: string;
  filter: StoreFilter;
  sort: StoreSort;
}

export const NEW_STORE_DAYS = 30;
const DAY_MS = 24 * 60 * 60 * 1000;

/** 매장별 메뉴 요약 — 메뉴 목록은 서버가 삭제된 메뉴를 빼고 id 오름차순(등록순)으로 줌 */
export function summarizeMenus(menus: Menu[]): Map<number, MenuSummary> {
  const byStore = new Map<number, MenuSummary>();
  for (const m of menus) {
    const s = byStore.get(m.storeId);
    if (s) {
      s.count += 1;
      s.minPrice = Math.min(s.minPrice, m.price);
      s.names.push(m.name);
    } else {
      byStore.set(m.storeId, { count: 1, minPrice: m.price, names: [m.name] });
    }
  }
  return byStore;
}

/** 등록 후 30일 이내 — createdAt은 시간대 표기 없는 서버 시각(Asia/Seoul)이라 브라우저 현지 시각으로 해석됨 (국내 사용 기준 차이 없음) */
export function isNewStore(createdAt: string, now: Date): boolean {
  const created = new Date(createdAt);
  if (Number.isNaN(created.getTime())) return false;
  const age = now.getTime() - created.getTime();
  return age >= 0 && age < NEW_STORE_DAYS * DAY_MS;
}

/** 검색어 비교용 — 대소문자·공백 차이를 무시 ("샘플 매장" = "샘플매장") */
export function normalizeText(value: string): string {
  return value.toLocaleLowerCase('ko-KR').replace(/\s+/g, '');
}

export interface CatalogItem {
  store: Store;
  menu: MenuSummary | null;
  isNew: boolean;
  /** 메뉴 이름으로 찾아졌으면 그 메뉴 (카드에 "아메리카노 판매" 같이 표시) */
  matchedMenu: string | null;
}

/**
 * menus가 null이면 메뉴 정보를 모름(조회 실패·상한 초과) → 메뉴 기반 필터·정렬·검색은 적용하지 않음
 */
export function buildCatalog(
  stores: Store[],
  menus: Map<number, MenuSummary> | null,
  query: CatalogQuery,
  now: Date,
): CatalogItem[] {
  const q = normalizeText(query.q);
  const items: CatalogItem[] = [];

  for (const store of stores) {
    const menu = menus ? (menus.get(store.id) ?? { count: 0, minPrice: 0, names: [] }) : null;
    const item: CatalogItem = { store, menu, isNew: isNewStore(store.createdAt, now), matchedMenu: null };

    if (query.filter === 'orderable' && menu && menu.count === 0) continue;
    if (query.filter === 'new' && !item.isNew) continue;

    if (q) {
      const nameHit = normalizeText(store.name).includes(q);
      const menuHit = menu?.names.find((n) => normalizeText(n).includes(q)) ?? null;
      if (!nameHit && !menuHit) continue;
      if (!nameHit) item.matchedMenu = menuHit;
    }
    items.push(item);
  }

  const byId = (a: CatalogItem, b: CatalogItem) => a.store.id - b.store.id;
  const compare: Record<StoreSort, (a: CatalogItem, b: CatalogItem) => number> = {
    default: byId, // 서버 기본 순서(등록순)
    newest: (a, b) => b.store.createdAt.localeCompare(a.store.createdAt) || b.store.id - a.store.id,
    name: (a, b) => a.store.name.localeCompare(b.store.name, 'ko-KR') || byId(a, b),
    menus: (a, b) => (b.menu?.count ?? 0) - (a.menu?.count ?? 0) || byId(a, b),
  };
  return items.sort(compare[query.sort]);
}

/** URL 쿼리 → 조회 조건 (알 수 없는 값은 기본값) — 상세에서 뒤로 와도 조건 유지 */
export function parseQuery(params: URLSearchParams): CatalogQuery {
  const filter = params.get('filter');
  const sort = params.get('sort');
  return {
    q: params.get('q') ?? '',
    filter: filter === 'orderable' || filter === 'new' ? filter : 'all',
    sort: sort === 'newest' || sort === 'name' || sort === 'menus' ? sort : 'default',
  };
}

export function toSearchParams(query: CatalogQuery): URLSearchParams {
  const p = new URLSearchParams();
  if (query.q.trim()) p.set('q', query.q);
  if (query.filter !== 'all') p.set('filter', query.filter);
  if (query.sort !== 'default') p.set('sort', query.sort);
  return p;
}
