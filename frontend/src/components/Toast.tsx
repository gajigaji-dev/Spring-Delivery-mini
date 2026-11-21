import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import { IconCheck } from './Icons';

type Tone = 'ok' | 'error';

interface ToastValue {
  notify: (message: string, tone?: Tone) => void;
  /** 오류는 문의 코드(traceId)를 붙여 보여 줌 — 문의 시 서버 로그를 찾을 수 있도록 */
  notifyError: (error: unknown) => void;
}

const ToastContext = createContext<ToastValue | null>(null);

export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    const base = error.status === 503 ? '잠시 후 다시 시도해 주세요. (요청은 반영되지 않았습니다)' : error.message;
    return error.traceId ? `${base} · 문의 코드 ${error.traceId}` : base;
  }
  return '네트워크 오류가 발생했습니다.';
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ message: string; tone: Tone } | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  const notify = useCallback((message: string, tone: Tone = 'ok') => {
    clearTimeout(timer.current);
    setToast({ message, tone });
    // 오류는 문의 코드를 옮겨 적을 시간이 필요해 더 오래 둠
    timer.current = setTimeout(() => setToast(null), tone === 'error' ? 6000 : 2400);
  }, []);

  const notifyError = useCallback((error: unknown) => notify(errorText(error), 'error'), [notify]);

  useEffect(() => () => clearTimeout(timer.current), []);

  const value = useMemo(() => ({ notify, notifyError }), [notify, notifyError]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      {toast && (
        <div className="toast" role={toast.tone === 'error' ? 'alert' : 'status'}>
          <i className={`toast__icon${toast.tone === 'error' ? ' toast__icon--error' : ''}`} aria-hidden="true">
            {toast.tone === 'error' ? <b>!</b> : <IconCheck size={14} />}
          </i>
          {toast.message}
        </div>
      )}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('ToastProvider 밖에서 useToast를 사용했습니다');
  return ctx;
}
