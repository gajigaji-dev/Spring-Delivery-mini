import { useState } from 'react';
import { storeApi } from '../api/endpoints';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { ErrorMessage } from '../components/ErrorMessage';
import { Pager } from '../components/Pager';
import { PrefetchLink } from '../components/PrefetchLink';
import { useAsync } from '../hooks/useAsync';

export default function StoresPage() {
  const [page, setPage] = useState(0);
  const { data, error, loading } = useAsync(() => storeApi.list(page), [page]);

  return (
    <section>
      <h1>매장</h1>
      <ErrorMessage error={error} />
      {loading && <DelayedSpinner />}
      {data && (
        <>
          {data.items.length === 0 && <p className="notice">등록된 매장이 없습니다.</p>}
          <ul className="grid list">
            {data.items.map((s) => (
              <li key={s.id} className="card">
                <PrefetchLink to={`/stores/${s.id}`} page="storeDetail">
                  {s.name}
                </PrefetchLink>
              </li>
            ))}
          </ul>
          <Pager page={page} hasNext={data.hasNext} onChange={setPage} />
        </>
      )}
    </section>
  );
}
