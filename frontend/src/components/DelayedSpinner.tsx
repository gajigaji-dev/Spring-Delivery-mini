import { useEffect, useState } from 'react';

/** 0.4초 안에 끝나는 로딩은 표시하지 않음 — 짧은 깜빡임이 더 느리게 느껴지므로 */
export function DelayedSpinner({ delayMs = 400 }: { delayMs?: number }) {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setVisible(true), delayMs);
    return () => clearTimeout(t);
  }, [delayMs]);
  if (!visible) return null;
  return (
    <div className="spinner" role="status" aria-live="polite">
      <span className="spinner__dot" aria-hidden="true" />
      불러오는 중…
    </div>
  );
}
