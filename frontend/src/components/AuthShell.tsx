import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { demoAccounts } from '../auth/demoAccounts';
import { IconReceipt, IconBag, IconStore, Logo } from './Icons';
import { SERVICE_NAME } from './Layout';

/** 로그인·가입 공통 화면: 왼쪽은 제품 조각(주문 티켓·입금 예정), 오른쪽은 폼 패널 */
export function AuthShell({ topRight, children }: { topRight: ReactNode; children: ReactNode }) {
  const demo = demoAccounts.length > 0;
  return (
    <div className="login">
      <header className="wrap login__top">
        <Link to="/" className="logo">
          <Logo size={34} />
          <b>{SERVICE_NAME}</b>
        </Link>
        <div className="login__top-right">
          {demo && <span className="demo">DEMO</span>}
          {topRight}
        </div>
      </header>
      <main className="wrap lgrid">
        <section className="lintro">
          <div className="lintro__copy">
            <span className="eyebrow" style={{ color: 'var(--tomato-600)' }}>
              배달 매장 운영 콘솔
            </span>
            <h1>
              주문은 빠르게,
              <br />
              정산은 투명하게.
            </h1>
            <p>들어온 주문을 바로 수락하고, 매출이 언제 얼마나 입금되는지 한 화면에서 확인하세요.</p>
          </div>
          {/* 실제 화면 형태를 보여 주는 예시 카드 — 수치는 예시 */}
          <div className="pv hide-sm" role="img" aria-label="주문 티켓과 입금 예정 금액 화면 예시">
            <div className="pv-t">
              <div className="ocard__head">
                <span className="chip c-wait">새 주문</span>
                <span className="num muted" style={{ fontSize: 13, fontWeight: 700 }}>
                  14:32 · 방금
                </span>
              </div>
              <div style={{ fontSize: 16, fontWeight: 800, margin: '12px 0 4px' }}>아메리카노 × 2 외 1건</div>
              <div className="ocard__head" style={{ marginTop: 12 }}>
                <b className="num" style={{ fontSize: 20 }}>
                  ₩15,800
                </b>
                <span className="btn btn--sm">주문 수락</span>
              </div>
            </div>
            <div className="pv-s">
              <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--dark-label)' }}>입금 예정</div>
              <div className="num" style={{ fontSize: 28, fontWeight: 800, letterSpacing: '-.03em', marginTop: 6 }}>
                ₩709,536
              </div>
              <div style={{ fontSize: 12.5, color: 'var(--dark-text)', marginTop: 4 }}>매출 ₩733,750 − 수수료 3.3%</div>
            </div>
          </div>
          <div className="feat">
            <div>
              <i>
                <IconBag />
              </i>
              실시간 주문 처리
            </div>
            <div>
              <i>
                <IconReceipt />
              </i>
              날짜별 정산 전표
            </div>
            <div>
              <i>
                <IconStore />
              </i>
              여러 매장 전환
            </div>
          </div>
        </section>
        <section className="lpanel">{children}</section>
      </main>
      <footer className="wrap foot">
        <span>{SERVICE_NAME} 점주 콘솔</span>
        <span>문의 시 화면의 문의 코드를 함께 전달해 주세요</span>
      </footer>
    </div>
  );
}
