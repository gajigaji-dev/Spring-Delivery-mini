import { ApiError } from '../api/client';

/** 오류 문구 + traceId — 사용자가 문의할 때 traceId로 서버 로그를 찾을 수 있도록 함께 보여줌 */
export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null;
  const message =
    error instanceof ApiError
      ? error.status === 503
        ? '잠시 후 다시 시도해 주세요. (요청은 반영되지 않았습니다)'
        : error.message
      : '네트워크 오류가 발생했습니다.';
  return (
    <div className="error" role="alert">
      {message}
      {error instanceof ApiError && error.traceId && <small className="error__trace">추적 ID {error.traceId}</small>}
    </div>
  );
}
