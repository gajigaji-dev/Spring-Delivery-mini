import { describe, expect, it } from 'vitest';
import type { Menu, Store } from '../api/types';
import { buildCatalog, isNewStore, parseQuery, summarizeMenus, toSearchParams, type CatalogQuery } from './storeCatalog';

const NOW = new Date('2026-11-21T12:00:00');

function store(id: number, name: string, createdAt: string): Store {
  return { id, name, ownerId: 1, createdAt, updatedAt: createdAt };
}

function menu(id: number, storeId: number, name: string, price: number): Menu {
  return { id, storeId, name, price, description: null, createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00' };
}

const stores = [
  store(1, '샘플매장 성수점', '2026-01-10T10:00:00'),
  store(2, '가나다 분식', '2026-11-15T10:00:00'), // 신규
  store(3, '피자 하우스', '2026-06-01T10:00:00'), // 메뉴 없음
];
const menus = summarizeMenus([
  menu(1, 1, '아메리카노', 4500),
  menu(2, 1, '카페라떼', 5300),
  menu(3, 2, '떡볶이', 6000),
  menu(4, 1, '크로와상', 3800),
]);
const q = (over: Partial<CatalogQuery> = {}): CatalogQuery => ({ q: '', filter: 'all', sort: 'default', ...over });
const ids = (items: ReturnType<typeof buildCatalog>) => items.map((i) => i.store.id);

describe('summarizeMenus', () => {
  it('매장별 메뉴 수·최저가·이름(등록순)을 모은다', () => {
    expect(menus.get(1)).toEqual({ count: 3, minPrice: 3800, names: ['아메리카노', '카페라떼', '크로와상'] });
    expect(menus.get(3)).toBeUndefined();
  });
});

describe('isNewStore', () => {
  it('등록 30일 이내만 신규', () => {
    expect(isNewStore('2026-11-15T10:00:00', NOW)).toBe(true);
    expect(isNewStore('2026-10-01T10:00:00', NOW)).toBe(false);
    expect(isNewStore('잘못된 값', NOW)).toBe(false);
  });
});

describe('buildCatalog', () => {
  it('기본은 서버 순서(등록순), 메뉴 없는 매장은 메뉴 0개로 표시', () => {
    const items = buildCatalog(stores, menus, q(), NOW);
    expect(ids(items)).toEqual([1, 2, 3]);
    expect(items[2].menu).toEqual({ count: 0, minPrice: 0, names: [] });
  });

  it('매장 이름은 공백·대소문자를 무시하고 찾는다', () => {
    expect(ids(buildCatalog(stores, menus, q({ q: '샘플 매장' }), NOW))).toEqual([1]);
  });

  it('메뉴 이름으로도 찾고, 어떤 메뉴로 찾았는지 알려 준다', () => {
    const items = buildCatalog(stores, menus, q({ q: '라떼' }), NOW);
    expect(ids(items)).toEqual([1]);
    expect(items[0].matchedMenu).toBe('카페라떼');
  });

  it('메뉴 정보를 모르면(null) 메뉴 검색·주문 가능 필터를 적용하지 않는다', () => {
    expect(ids(buildCatalog(stores, null, q({ q: '라떼' }), NOW))).toEqual([]);
    expect(ids(buildCatalog(stores, null, q({ filter: 'orderable' }), NOW))).toEqual([1, 2, 3]);
  });

  it('필터: 주문 가능(메뉴 있음)·신규 매장', () => {
    expect(ids(buildCatalog(stores, menus, q({ filter: 'orderable' }), NOW))).toEqual([1, 2]);
    expect(ids(buildCatalog(stores, menus, q({ filter: 'new' }), NOW))).toEqual([2]);
  });

  it('정렬: 최신 등록순·이름순·메뉴 많은 순', () => {
    expect(ids(buildCatalog(stores, menus, q({ sort: 'newest' }), NOW))).toEqual([2, 3, 1]);
    expect(ids(buildCatalog(stores, menus, q({ sort: 'name' }), NOW))).toEqual([2, 1, 3]);
    expect(ids(buildCatalog(stores, menus, q({ sort: 'menus' }), NOW))).toEqual([1, 2, 3]);
  });
});

describe('URL 쿼리', () => {
  it('기본값은 쿼리에 넣지 않고, 알 수 없는 값은 기본값으로 읽는다', () => {
    expect(toSearchParams(q()).toString()).toBe('');
    expect(toSearchParams(q({ q: '라떼', filter: 'new', sort: 'name' })).toString()).toBe('q=%EB%9D%BC%EB%96%BC&filter=new&sort=name');
    expect(parseQuery(new URLSearchParams('filter=foo&sort=bar'))).toEqual(q());
  });
});
