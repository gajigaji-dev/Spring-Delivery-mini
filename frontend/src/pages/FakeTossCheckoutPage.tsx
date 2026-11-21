import { useMemo, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { fakeTossApi, type FakeTossScenario } from '../api/endpoints';
import { ErrorMessage } from '../components/ErrorMessage';
import { Money } from '../components/format';
import { tossReturnUrls, withTossFailParams, withTossSuccessParams } from '../payments/tossSdk';

const SCENARIOS: { scenario: FakeTossScenario; label: string; description: string }[] = [
  { scenario: 'SUCCESS', label: '결제하기', description: '정상 승인' },
  { scenario: 'CARD_REJECTED', label: '카드 거절', description: '서버 승인 때 카드사가 거절 → 결제되지 않음' },
  { scenario: 'RESPONSE_LOST', label: '승인 응답 유실', description: '승인은 됐는데 응답이 사라짐 → 서버가 조회로 확인해 기록' },
  { scenario: 'PG_DELAYED', label: 'PG 지연', description: '첫 승인 요청이 처리되지 않음 → 결과 확인 중 → 자동 재시도로 승인' },
];

/**
 * 가짜 결제창 (서버 toss.payments.mode=fake, V21) — 토스 서버에 접속하지 않고 결제 흐름 전체를 시연.
 * 실제 결제창이 하는 일(카드 인증 → 결제 키 발급 → successUrl/failUrl 이동)만 흉내 내고,
 * 이후의 성공 화면·서버 승인·금액 대조·멱등 처리는 실제 토스 결제와 같은 코드로 동작.
 */
export default function FakeTossCheckoutPage() {
  const { search } = useLocation();
  const params = useMemo(() => {
    const q = new URLSearchParams(search);
    const orderId = Number(q.get('order'));
    const amount = Number(q.get('amount'));
    const tossOrderId = q.get('tossOrderId');
    if (!Number.isSafeInteger(orderId) || orderId <= 0 || !Number.isSafeInteger(amount) || amount <= 0 || !tossOrderId) {
      return null;
    }
    return { orderId, amount, tossOrderId, orderName: q.get('orderName') ?? '' };
  }, [search]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (!params) {
    return (
      <section>
        <h1>결제 정보가 올바르지 않습니다</h1>
        <Link to="/orders">주문 목록으로</Link>
      </section>
    );
  }

  const urls = tossReturnUrls(window.location.origin, params.orderId);

  // amount: 결제창에 넘어온 금액. "금액 조작"은 화면이 금액을 바꾼 경우를 재현 → 서버가 주문 총액과 대조해 거절해야 함
  const pay = async (scenario: FakeTossScenario, amount = params.amount) => {
    setBusy(true);
    setError(null);
    try {
      const { paymentKey } = await fakeTossApi.authorize(params.tossOrderId, amount, scenario);
      window.location.assign(withTossSuccessParams(urls.successUrl, paymentKey, params.tossOrderId, amount));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  const close = () =>
    window.location.assign(
      withTossFailParams(urls.failUrl, 'PAY_PROCESS_CANCELED', '사용자에 의해 결제가 취소되었습니다.', params.tossOrderId),
    );

  return (
    <section>
      <h1>가짜 결제창</h1>
      <p className="notice">토스페이먼츠 서버에 접속하지 않는 시연용 결제창입니다. 실제 돈은 오가지 않습니다.</p>
      <p>
        {params.orderName} · <strong><Money value={params.amount} /></strong>
      </p>
      <p className="muted num">주문번호 {params.tossOrderId}</p>
      <ErrorMessage error={error} />
      <div className="table-wrap">
        <table>
          <tbody>
            {SCENARIOS.map((s) => (
              <tr key={s.scenario}>
                <td>
                  <button type="button" className="btn" disabled={busy} onClick={() => void pay(s.scenario)}>
                    {s.label}
                  </button>
                </td>
                <td className="muted">{s.description}</td>
              </tr>
            ))}
            <tr>
              <td>
                <button type="button" className="btn btn--ghost" disabled={busy} onClick={() => void pay('SUCCESS', 1)}>
                  금액 조작 (1원)
                </button>
              </td>
              <td className="muted">결제창 금액을 1원으로 바꿔 인증 → 서버가 주문 총액과 달라 승인하지 않음</td>
            </tr>
            <tr>
              <td>
                <button type="button" className="btn btn--ghost" disabled={busy} onClick={close}>
                  결제창 닫기
                </button>
              </td>
              <td className="muted">구매자가 결제를 취소 → 실패 화면 (결제되지 않음)</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  );
}
