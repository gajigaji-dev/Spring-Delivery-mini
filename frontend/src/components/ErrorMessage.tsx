import { ApiError } from '../api/client';
import { userMessage } from '../api/errorMessages';
import { IconAlert } from './Icons';

/** 오류 문구 + 문의 코드(traceId) — 사용자가 문의할 때 traceId로 서버 로그를 찾을 수 있도록 함께 보여줌 */
export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null;
  // 문구는 오류 코드 기준 (api/errorMessages.ts, #77)
  const message = userMessage(error);
  return (
    <div className="error" role="alert">
      <IconAlert />
      <span className="error__body">
        {message}
        {error instanceof ApiError && error.traceId && (
          <small className="error__trace num">문의 코드 {error.traceId}</small>
        )}
      </span>
    </div>
  );
}
