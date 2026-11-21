import { useMemo } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { failMessage, parseFailParams } from '../payments/tossConfirm';

/** 토스 결제창 실패·취소 후 돌아오는 화면 (V20) — 서버 승인 전이라 결제되지 않은 상태 */
export default function TossFailPage() {
  const { search } = useLocation();
  const params = useMemo(() => parseFailParams(search), [search]);

  return (
    <section>
      <h1>결제하지 않았습니다</h1>
      <p className="notice">{failMessage(params)}</p>
      {params.code && <p className="muted num">오류 코드 {params.code}</p>}
      <Link className="btn" to={params.orderId ? `/orders/${params.orderId}` : '/orders'}>
        주문으로 돌아가기
      </Link>
    </section>
  );
}
