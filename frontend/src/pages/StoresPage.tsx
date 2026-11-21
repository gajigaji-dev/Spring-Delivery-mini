import { useDeferredValue, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { userMessage } from '../api/errorMessages';
import { IconAlert, IconRefresh, IconSearch, IconStore, IconX } from '../components/Icons';
import { won } from '../components/format';
import { PrefetchLink } from '../components/PrefetchLink';
import { useAsync } from '../hooks/useAsync';
import { loadCatalogData, MAX_PAGES } from '../stores/loadCatalog';
import {
  buildCatalog,
  parseQuery,
  toSearchParams,
  type CatalogItem,
  type CatalogQuery,
  type StoreFilter,
  type StoreSort,
} from '../stores/storeCatalog';
import { StoreCover } from '../stores/StoreCover';
import '../styles/stores.css';

const FILTERS: { id: StoreFilter; label: string; needsMenus?: boolean }[] = [
  { id: 'all', label: '전체' },
  { id: 'orderable', label: '주문 가능', needsMenus: true },
  { id: 'new', label: '신규 매장' },
];

const SORTS: { id: StoreSort; label: string; needsMenus?: boolean }[] = [
  { id: 'default', label: '등록순' },
  { id: 'newest', label: '최신 등록순' },
  { id: 'name', label: '이름순' },
  { id: 'menus', label: '메뉴 많은 순', needsMenus: true },
];

/**
 * 고객 매장 목록 (`/stores`) — 검색·필터·정렬은 프론트에서 (서버 API에 없음, stores/storeCatalog.ts).
 * 조건은 URL 쿼리에 둬 매장 상세에서 뒤로 와도 그대로. 매장 정보는 API 값과 메뉴에서 계산한 값만 표시.
 */
export default function StoresPage() {
  const { data, error, loading, reload } = useAsync(loadCatalogData, []);
  const [params, setParams] = useSearchParams();
  const query = parseQuery(params);
  // 입력은 바로 반영하고 목록 계산은 한 박자 늦게 → 매장이 많아도 타이핑이 끊기지 않음
  const deferredQuery = useDeferredValue(query.q);
  const [now] = useState(() => new Date());

  const hasMenus = data?.menus != null;
  const effective: CatalogQuery = {
    q: deferredQuery,
    // 메뉴 정보를 못 받았으면 메뉴 기반 조건은 기본값으로
    filter: !hasMenus && query.filter === 'orderable' ? 'all' : query.filter,
    sort: !hasMenus && query.sort === 'menus' ? 'default' : query.sort,
  };
  const items = useMemo(
    () => (data ? buildCatalog(data.stores, data.menus, effective, now) : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [data, effective.q, effective.filter, effective.sort, now],
  );

  const update = (next: Partial<CatalogQuery>) =>
    // 입력마다 방문 기록이 쌓이지 않도록 replace
    setParams(toSearchParams({ ...query, ...next }), { replace: true, preventScrollReset: true });
  const filtered = query.q.trim() !== '' || effective.filter !== 'all';
  const reset = () => setParams(new URLSearchParams(), { replace: true });

  return (
    <section className="stores">
      <header className="stores__head">
        <h1>어떤 매장에서 주문할까요?</h1>
        <p>매장을 둘러보고 메뉴를 담아 바로 주문하세요. 매장 이름이나 메뉴로 찾을 수 있어요.</p>
      </header>

      <div className="stores__tools">
        <div className="ssearch" role="search">
          <IconSearch size={20} />
          <label htmlFor="store-search" className="sr-only">
            매장 또는 메뉴 검색
          </label>
          <input
            id="store-search"
            type="search"
            value={query.q}
            onChange={(e) => update({ q: e.target.value })}
            placeholder={hasMenus || !data ? '매장 이름이나 메뉴 검색' : '매장 이름 검색'}
            autoComplete="off"
            enterKeyHint="search"
            maxLength={50}
          />
          {query.q && (
            <button type="button" className="ssearch__clear" onClick={() => update({ q: '' })} aria-label="검색어 지우기">
              <IconX size={18} />
            </button>
          )}
        </div>
        <label className="ssort">
          <span className="sr-only">정렬</span>
          <select value={effective.sort} onChange={(e) => update({ sort: e.target.value as StoreSort })}>
            {SORTS.filter((s) => !s.needsMenus || hasMenus || !data).map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="schips" role="group" aria-label="매장 필터">
        {FILTERS.filter((f) => !f.needsMenus || hasMenus || !data).map((f) => (
          <button
            key={f.id}
            type="button"
            className="schip"
            aria-pressed={effective.filter === f.id}
            onClick={() => update({ filter: f.id })}
          >
            {f.label}
          </button>
        ))}
      </div>

      {error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : loading && !data ? (
        <SkeletonGrid />
      ) : data && data.stores.length === 0 ? (
        <div className="sstate">
          <span className="sstate__icon">
            <IconStore size={26} />
          </span>
          <b>아직 등록된 매장이 없어요</b>
          <p>매장이 문을 열면 이곳에 보여 드릴게요.</p>
        </div>
      ) : (
        <>
          <div className="sbar">
            {/* 결과 수는 스크린리더에도 바뀔 때마다 알림 */}
            <p role="status" aria-live="polite">
              {filtered ? '검색 결과' : '전체 매장'} <b className="num">{items.length}</b>곳
            </p>
            {(filtered || query.sort !== 'default') && (
              <button type="button" className="link sbar__reset" onClick={reset}>
                <IconRefresh size={16} /> 조건 초기화
              </button>
            )}
          </div>

          {items.length === 0 ? (
            <div className="sstate">
              <span className="sstate__icon sstate__icon--hold">
                <IconSearch size={26} />
              </span>
              <b>{query.q.trim() ? `'${query.q.trim()}'에 맞는 매장이 없어요` : '조건에 맞는 매장이 없어요'}</b>
              <p>검색어를 바꾸거나 필터를 해제해 보세요.</p>
              <button type="button" className="btn btn--ghost" onClick={reset}>
                조건 초기화
              </button>
            </div>
          ) : (
            <ul className="sgrid">
              {items.map((item) => (
                <StoreCard key={item.store.id} item={item} />
              ))}
            </ul>
          )}

          {data && !data.storesComplete && (
            <p className="notice sfoot">매장이 많아 처음 {MAX_PAGES * 100}곳까지만 보여 드려요.</p>
          )}
          {data && !hasMenus && (
            <p className="notice sfoot">메뉴 정보를 불러오지 못해 메뉴 검색과 메뉴 정보는 빼고 보여 드려요.</p>
          )}
        </>
      )}
    </section>
  );
}

function StoreCard({ item }: { item: CatalogItem }) {
  const { store, menu, isNew, matchedMenu } = item;
  const preview = menu?.names.slice(0, 3) ?? [];
  return (
    <li>
      <PrefetchLink to={`/stores/${store.id}`} page="storeDetail" className="scard">
        <div className="scard__media">
          <StoreCover id={store.id} name={store.name} />
          {isNew && <span className="scard__badge">신규</span>}
        </div>
        <div className="scard__body">
          <h2 className="scard__name">{store.name}</h2>
          {menu &&
            (menu.count > 0 ? (
              <p className="scard__meta">
                메뉴 <b className="num">{menu.count}</b>개 <span aria-hidden="true">·</span>{' '}
                <span className="num">{won(menu.minPrice)}</span>부터
              </p>
            ) : (
              <p className="scard__meta scard__meta--muted">메뉴 준비 중</p>
            ))}
          {matchedMenu ? (
            <p className="scard__menus">
              <mark>{matchedMenu}</mark> 판매 중
            </p>
          ) : (
            preview.length > 0 && <p className="scard__menus">{preview.join(' · ')}</p>
          )}
        </div>
      </PrefetchLink>
    </li>
  );
}

function SkeletonGrid() {
  return (
    <div className="sgrid sgrid--skeleton" role="status" aria-label="매장을 불러오는 중">
      {Array.from({ length: 8 }, (_, i) => (
        <div key={i} className="scard scard--skeleton" aria-hidden="true">
          <div className="scard__media skel" />
          <div className="scard__body">
            <span className="skel skel--title" />
            <span className="skel skel--line" />
            <span className="skel skel--line skel--short" />
          </div>
        </div>
      ))}
    </div>
  );
}

function ErrorState({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="sstate" role="alert">
      <span className="sstate__icon sstate__icon--fail">
        <IconAlert size={26} />
      </span>
      <b>매장을 불러오지 못했어요</b>
      <p>{userMessage(error)}</p>
      {error instanceof ApiError && error.traceId && <small className="num muted">문의 코드 {error.traceId}</small>}
      <button type="button" className="btn" onClick={onRetry}>
        <IconRefresh size={18} /> 다시 시도
      </button>
    </div>
  );
}
