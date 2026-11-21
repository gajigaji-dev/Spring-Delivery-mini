import { useState, type FormEvent } from 'react';
import { menuApi, storeApi } from '../../api/endpoints';
import type { Store } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { DelayedSpinner } from '../../components/DelayedSpinner';
import { ErrorMessage } from '../../components/ErrorMessage';
import { PrefetchLink } from '../../components/PrefetchLink';
import { useAsync } from '../../hooks/useAsync';

export default function OwnerStoresPage() {
  const { me, reloadMe } = useAuth();
  const storeIds = me?.storeIds ?? [];
  // 내 매장 id는 /me(DB 기준)에서 받음 → 매장 수만큼 단건 조회
  const { data: stores, error, loading } = useAsync(
    () => Promise.all(storeIds.map((id) => storeApi.get(id))),
    [storeIds.join(',')],
  );
  const [name, setName] = useState('');
  const [formError, setFormError] = useState<unknown>(null);

  const onCreate = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    try {
      await storeApi.create(name);
      setName('');
      await reloadMe(); // storeIds 갱신
    } catch (err) {
      setFormError(err);
    }
  };

  return (
    <section>
      <h1>내 매장</h1>
      <ErrorMessage error={error} />
      {loading && <DelayedSpinner />}
      {stores?.length === 0 && <p className="notice">매장이 없습니다.</p>}
      <ul className="list">
        {stores?.map((s) => <StoreCard key={s.id} store={s} />)}
      </ul>

      <h2>매장 추가</h2>
      <form className="form" onSubmit={onCreate}>
        <label>
          매장 이름
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={50} required />
        </label>
        <ErrorMessage error={formError} />
        <button className="btn">추가</button>
      </form>
    </section>
  );
}

function StoreCard({ store }: { store: Store }) {
  const [menuName, setMenuName] = useState('');
  const [price, setPrice] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState<string | null>(null);

  const onAddMenu = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setMessage(null);
    try {
      const menu = await menuApi.create({ storeId: store.id, name: menuName, price: Number(price) });
      setMenuName('');
      setPrice('');
      setMessage(`${menu.name} 등록됨`);
    } catch (err) {
      setError(err);
    }
  };

  return (
    <li className="card">
      <PrefetchLink to={`/stores/${store.id}`} page="storeDetail">
        <strong>{store.name}</strong>
      </PrefetchLink>{' '}
      <span className="muted">#{store.id}</span>
      <form className="row" onSubmit={onAddMenu} style={{ marginTop: 'var(--space-2)' }}>
        <input placeholder="메뉴 이름" value={menuName} onChange={(e) => setMenuName(e.target.value)} maxLength={50} required />
        <input
          placeholder="가격(원)"
          type="number"
          min={1}
          step={1}
          inputMode="numeric"
          value={price}
          onChange={(e) => setPrice(e.target.value)}
          required
        />
        <button className="btn btn--ghost">메뉴 등록</button>
      </form>
      {message && <p className="muted">{message}</p>}
      <ErrorMessage error={error} />
    </li>
  );
}
