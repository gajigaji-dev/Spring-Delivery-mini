import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { menuApi, storeApi } from '../api/endpoints';
import type { Menu } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { ErrorMessage } from '../components/ErrorMessage';
import { Money } from '../components/format';
import { useAsync } from '../hooks/useAsync';

export default function StoreDetailPage() {
  const storeId = Number(useParams().id);
  const { me } = useAuth();
  const cart = useCart();
  const store = useAsync(() => storeApi.get(storeId), [storeId]);

  // 메뉴 API에 매장 필터가 없어 전체 메뉴를 페이지로 받아 거른다 (임시).
  // 메뉴가 많아지면 서버에 storeId 조건을 추가하는 편이 맞음
  const [menus, setMenus] = useState<Menu[]>([]);
  const [menuPage, setMenuPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [menuError, setMenuError] = useState<unknown>(null);
  const [menuLoading, setMenuLoading] = useState(true);

  useEffect(() => {
    let active = true;
    setMenuLoading(true);
    menuApi
      .list(menuPage)
      .then((r) => {
        if (!active) return;
        setMenus((prev) => [...(menuPage === 0 ? [] : prev), ...r.items.filter((m) => m.storeId === storeId)]);
        setHasNext(r.hasNext);
      })
      .catch((e) => active && setMenuError(e))
      .finally(() => active && setMenuLoading(false));
    return () => {
      active = false;
    };
  }, [storeId, menuPage]);

  return (
    <section>
      <ErrorMessage error={store.error} />
      {store.data && <h1>{store.data.name}</h1>}
      <h2>메뉴</h2>
      <ErrorMessage error={menuError} />
      <ul className="list">
        {menus.map((m) => (
          <li key={m.id} className="card row" style={{ justifyContent: 'space-between' }}>
            <div>
              <strong>{m.name}</strong> <Money value={m.price} />
              {m.description && <div className="muted">{m.description}</div>}
            </div>
            {me?.role === 'CUSTOMER' && (
              <button type="button" className="btn" onClick={() => cart.add(m)}>
                담기
              </button>
            )}
          </li>
        ))}
      </ul>
      {menuLoading && <DelayedSpinner />}
      {!menuLoading && menus.length === 0 && !hasNext && <p className="notice">메뉴가 없습니다.</p>}
      {hasNext && (
        <button type="button" className="btn btn--ghost" disabled={menuLoading} onClick={() => setMenuPage((p) => p + 1)}>
          메뉴 더 찾기
        </button>
      )}
    </section>
  );
}
