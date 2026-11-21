import type { ReactNode } from 'react';
import { IconBell, IconCheck, IconChevronDown, IconStore } from '../components/Icons';
import { useCountUp, useInView } from './motion';

/*
 * 랜딩용 화면 목업 — 실제 화면(주문 티켓·정산 블록·주소 입력)의 구성과 문구를 그대로 옮긴 축소판.
 * 금액·시각·매장명은 예시 값. 목업 내부는 장식이라 aria-hidden, 바깥 figure의 aria-label이 대체 텍스트.
 * 상태 문구는 components/format.tsx·사장님 화면과 같은 값 (수락 대기·배달 중·배달 완료 / 정산 예정·입금 대기·지급 완료)
 */

function Frame({ label, className = '', children }: { label: string; className?: string; children: ReactNode }) {
  return (
    <figure className={`mock ${className}`} role="img" aria-label={label}>
      <div aria-hidden="true">{children}</div>
    </figure>
  );
}

function BrowserBar({ title }: { title: string }) {
  return (
    <div className="mock__bar">
      <i />
      <i />
      <i />
      <span>{title}</span>
    </div>
  );
}

export function Ticket({
  time,
  menu,
  amount,
  chip,
  tone,
  action,
}: {
  time: string;
  menu: string;
  amount: string;
  chip: string;
  tone: 'wait' | 'prog' | 'done';
  action?: string;
}) {
  return (
    <div className={`mticket${tone === 'wait' ? ' mticket--wait' : ''}`}>
      <span className="mticket__time num">{time}</span>
      <div className="mticket__body">
        <b>{menu}</b>
        <span className={`chip chip--sm c-${tone}`}>{chip}</span>
      </div>
      <span className="mticket__amt num">{amount}</span>
      {action && <span className="btn btn--sm mticket__act">{action}</span>}
    </div>
  );
}

/* ---- 첫 화면: 사장님 콘솔(브라우저) + 고객 결제(휴대폰) + 새 주문 토스트 ---- */
export function HeroVisual() {
  return (
    <div className="hv" role="img" aria-label="고객 휴대폰의 결제 화면과 사장님 콘솔의 주문 관리 화면 예시">
      <div className="hv__console" aria-hidden="true">
        <BrowserBar title="주문 관리 · 성수점" />
        <div className="hv__console-in">
          <div className="hv__head">
            <b>오늘 들어온 주문</b>
            <span className="mlive">
              <i /> 실시간 연결됨
            </span>
          </div>
          <Ticket time="14:32" menu="아메리카노 × 2 외 1건" amount="₩15,800" chip="수락 대기" tone="wait" action="주문 수락" />
          <Ticket time="14:18" menu="카페라떼 × 1" amount="₩5,300" chip="배달 중" tone="prog" />
          <Ticket time="13:55" menu="콜드브루 × 3" amount="₩16,500" chip="배달 완료" tone="done" />
          <div className="hv__settle">
            <span>입금 예정</span>
            <b className="num">₩709,536</b>
          </div>
        </div>
      </div>

      <div className="hv__phone" aria-hidden="true">
        <div className="hv__notch" />
        <div className="hv__phone-in">
          <span className="eyebrow">주문 확인</span>
          <b className="hv__store">성수점</b>
          <div className="hv__line">
            <span>아메리카노 × 2</span>
            <span className="num">₩9,000</span>
          </div>
          <div className="hv__line">
            <span>크로와상 × 1</span>
            <span className="num">₩6,800</span>
          </div>
          <div className="hv__addr">
            <span className="hv__zip num">06236</span>
            서울 강남구 테헤란로 152
            <br />
            101동 1203호
          </div>
          <span className="btn btn--block hv__pay">₩15,800 카드 결제</span>
        </div>
      </div>

      <div className="hv__toast" aria-hidden="true">
        <i>
          <IconBell size={15} />
        </i>
        새 주문 · 결제 완료 <b className="num">₩15,800</b>
      </div>
    </div>
  );
}

/* ---- 고객 기능 ---- */
export function StoreMenuMock() {
  return (
    <Frame label="매장 메뉴를 장바구니에 담는 화면 예시" className="mock--phone">
      <div className="mm__store">
        <span className="mm__avatar">
          <IconStore size={18} />
        </span>
        <div>
          <b>성수점</b>
          <span className="muted">메뉴 10개</span>
        </div>
      </div>
      {[
        ['아메리카노', '₩4,500', true],
        ['카페라떼', '₩5,300', false],
        ['크로와상', '₩6,800', true],
      ].map(([name, price, added]) => (
        <div key={name as string} className="mm__row">
          <div>
            <b>{name}</b>
            <span className="num muted">{price}</span>
          </div>
          <span className={`mm__add${added ? ' is-added' : ''}`}>{added ? '담김' : '담기'}</span>
        </div>
      ))}
      <div className="mm__cart">
        <span>장바구니 2개</span>
        <b className="num">₩15,800</b>
      </div>
    </Frame>
  );
}

export function CheckoutMock() {
  return (
    <Frame label="우편번호 검색으로 주소를 입력하고 카드로 결제하는 화면 예시" className="mock--card">
      <span className="mf__legend">배달 주소</span>
      <div className="mf__row">
        <span className="mf__input mf__zip num">06236</span>
        <span className="mf__search">주소 검색</span>
      </div>
      <span className="mf__input">서울 강남구 테헤란로 152 (역삼동)</span>
      <span className="mf__input mf__focus">
        101동 1203호<i className="mf__caret" />
      </span>
      <div className="mf__sum">
        <span>결제 금액</span>
        <b className="num">₩15,800</b>
      </div>
      <span className="btn btn--block">카드 결제</span>
      <p className="mf__note">
        <IconCheck size={14} /> 응답이 끊겨 다시 눌러도 결제는 한 번만
      </p>
    </Frame>
  );
}

export function OrderTrackMock() {
  const steps = ['결제 완료', '배달 중', '배달 완료'];
  return (
    <Frame label="주문 상태 진행과 알림 목록 화면 예시" className="mock--card">
      <div className="mo__head">
        <b>주문 #1024</b>
        <span className="chip chip--sm c-prog">배달 중</span>
      </div>
      <ol className="mo__steps">
        {steps.map((s, i) => (
          <li key={s} className={i <= 1 ? 'is-done' : ''}>
            <i />
            {s}
          </li>
        ))}
      </ol>
      {/* 알림 문구는 서버 NotificationService와 같은 형식 */}
      <div className="mo__noti">
        <span className="mo__dot" />
        <div>
          <b>주문 #1024 결제가 완료되었습니다. (15,800원)</b>
          <span className="muted">14:32</span>
        </div>
      </div>
      <div className="mo__noti is-read">
        <span className="mo__dot" />
        <div>
          <b>주문 #1019 배달이 완료되었습니다.</b>
          <span className="muted">어제</span>
        </div>
      </div>
    </Frame>
  );
}

/* ---- 사장님 기능 ---- */
export function OwnerOrdersMock() {
  return (
    <Frame label="새 주문이 실시간으로 들어오는 사장님 주문 관리 화면 예시" className="mock--browser">
      <BrowserBar title="주문 관리" />
      <div className="mock__in">
        <div className="hv__head">
          <b>수락 대기 1건</b>
          <span className="mlive">
            <i /> 실시간 연결됨
          </span>
        </div>
        <div className="mo__new">
          <Ticket time="14:32" menu="아메리카노 × 2 외 1건" amount="₩15,800" chip="수락 대기" tone="wait" action="주문 수락" />
        </div>
        <Ticket time="14:18" menu="카페라떼 × 1" amount="₩5,300" chip="배달 중" tone="prog" action="배달 완료" />
      </div>
    </Frame>
  );
}

export function SettlementMock() {
  const [ref, visible] = useInView<HTMLDivElement>();
  const sales = useCountUp(733_750, visible);
  const fee = useCountUp(24_214, visible);
  const payout = useCountUp(709_536, visible);
  return (
    <figure className="mock mock--dark" role="img" aria-label="매출에서 수수료를 뺀 입금 예정 금액과 정산 상태 화면 예시">
      <div ref={ref} aria-hidden="true">
        <div className="ms__head">
          <span>11월 20일 정산 전표</span>
          <span className="chip chip--sm c-hold">입금 대기</span>
        </div>
        <div className="ms__row">
          <span>매출</span>
          <span className="num">₩{sales.toLocaleString('ko-KR')}</span>
        </div>
        <div className="ms__row ms__fee">
          <span>수수료 3.3%</span>
          <span className="num">− ₩{fee.toLocaleString('ko-KR')}</span>
        </div>
        <div className="ms__total">
          <span>입금 예정</span>
          <b className="num">₩{payout.toLocaleString('ko-KR')}</b>
        </div>
        <div className="ms__states">
          <span>정산 예정</span>
          <span className="is-on">입금 대기</span>
          <span>지급 완료</span>
        </div>
      </div>
    </figure>
  );
}

export function StoresMock() {
  return (
    <Frame label="여러 매장을 전환하고 메뉴를 관리하는 화면 예시" className="mock--card">
      <div className="mst__switch">
        <IconStore size={16} />
        <b>성수점</b>
        <IconChevronDown size={16} />
      </div>
      <div className="mst__menu">
        <span className="is-on">
          성수점 <IconCheck size={14} />
        </span>
        <span>판교점</span>
      </div>
      <div className="mst__list">
        {[
          ['아메리카노', '₩4,500'],
          ['카페라떼', '₩5,300'],
          ['콜드브루', '₩5,500'],
        ].map(([n, p]) => (
          <div key={n}>
            <span>{n}</span>
            <span className="num muted">{p}</span>
          </div>
        ))}
        <span className="mst__add">+ 메뉴 등록</span>
      </div>
    </Frame>
  );
}
