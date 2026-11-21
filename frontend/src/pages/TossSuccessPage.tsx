import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { tossApi } from '../api/endpoints';
import type { Payment } from '../api/types';
import { DelayedSpinner } from '../components/DelayedSpinner';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatDateTime, Money } from '../components/format';
import { confirmWithRetry, isRetryableConfirmError, parseSuccessParams } from '../payments/tossConfirm';

type State = { status: 'pending' } | { status: 'done'; payment: Payment } | { status: 'error'; error: unknown };

/**
 * 토스 결제창 성공 후 돌아오는 화면 (V20) — 여기서 서버 승인을 요청해야 실제 결제가 됨.
 * 결과를 모르는 오류(시간 초과 등)는 같은 요청으로 자동 재시도 → 그래도 모르면 '다시 확인' 버튼(같은 요청).
 * 결제창을 다시 여는 버튼은 두지 않음: 새 결제가 되어 이중 결제가 될 수 있음.
 */
export default function TossSuccessPage() {
  const { search } = useLocation();
  const params = useMemo(() => parseSuccessParams(search), [search]);
  const [state, setState] = useState<State>({ status: 'pending' });
  const started = useRef(false);

  const confirm = useCallback(async () => {
    if (!params) return;
    setState({ status: 'pending' });
    try {
      const payment = await confirmWithRetry(() => tossApi.confirm(params));
      setState({ status: 'done', payment });
    } catch (error) {
      setState({ status: 'error', error });
    }
  }, [params]);

  useEffect(() => {
    // 개발 모드(StrictMode)의 effect 두 번 실행에도 요청은 한 번 — 두 번 가도 서버가 멱등하게 처리하지만 불필요한 호출을 줄임
    if (started.current) return;
    started.current = true;
    void confirm();
  }, [confirm]);

  if (!params) {
    return (
      <section>
        <h1>결제 정보가 올바르지 않습니다</h1>
        <p className="notice">결제창에서 돌아온 주소가 아닙니다. 주문 목록에서 결제 상태를 확인해 주세요.</p>
        <Link to="/orders">주문 목록으로</Link>
      </section>
    );
  }

  if (state.status === 'pending') {
    return (
      <section>
        <h1>결제를 승인하고 있습니다</h1>
        <p className="muted">창을 닫거나 새로 고치지 않아도 됩니다. 새로 고쳐도 결제는 한 번만 처리됩니다.</p>
        <DelayedSpinner />
      </section>
    );
  }

  if (state.status === 'done') {
    return (
      <section>
        <h1>결제가 완료되었습니다</h1>
        <p>
          <Money value={state.payment.amount} /> · {formatDateTime(state.payment.paidAt)}
        </p>
        <p className="muted">토스페이먼츠 테스트 결제입니다. 실제로 청구되지 않습니다.</p>
        <Link className="btn" to={`/orders/${params.orderId}`}>
          주문 상세 보기
        </Link>
      </section>
    );
  }

  const retryable = isRetryableConfirmError(state.error);
  return (
    <section>
      <h1>{retryable ? '결제 결과를 확인하지 못했습니다' : '결제가 완료되지 않았습니다'}</h1>
      <ErrorMessage error={state.error} />
      <div className="row">
        {retryable && (
          <button type="button" className="btn" onClick={() => void confirm()}>
            다시 확인
          </button>
        )}
        <Link className="btn btn--ghost" to={`/orders/${params.orderId}`}>
          주문으로 돌아가기
        </Link>
      </div>
    </section>
  );
}
