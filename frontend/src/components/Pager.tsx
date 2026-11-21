/** page 방식 목록 공통 — 전체 개수는 서버가 주지 않으므로(X-Has-Next만) 이전·다음만 */
export function Pager({ page, hasNext, onChange }: { page: number; hasNext: boolean; onChange: (p: number) => void }) {
  if (page === 0 && !hasNext) return null;
  return (
    <div className="row pager">
      <button type="button" className="btn btn--ghost" disabled={page === 0} onClick={() => onChange(page - 1)}>
        이전
      </button>
      <span className="muted num">{page + 1}</span>
      <button type="button" className="btn btn--ghost" disabled={!hasNext} onClick={() => onChange(page + 1)}>
        다음
      </button>
    </div>
  );
}
