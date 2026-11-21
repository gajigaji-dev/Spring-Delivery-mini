import { useRef, useState, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { BootScreen } from '../components/BootScreen';
import {
  IconArrowRight,
  IconBag,
  IconBell,
  IconCart,
  IconCheck,
  IconReceipt,
  IconStore,
  IconUserCheck,
  IconWallet,
  Logo,
} from '../components/Icons';
import { ROLE_HOME, SERVICE_NAME } from '../components/Layout';
import { PrefetchLink } from '../components/PrefetchLink';
import { goToSection, LandingHeader, SECTIONS } from '../landing/LandingHeader';
import {
  CheckoutMock,
  HeroVisual,
  OrderTrackMock,
  OwnerOrdersMock,
  SettlementMock,
  StoreMenuMock,
  StoresMock,
} from '../landing/mockups';
import { usePageMeta } from '../landing/motion';
import { Reveal } from '../landing/Reveal';
import '../styles/landing.css';

/**
 * 공식 랜딩 (`/`) — 비로그인 방문자에게 서비스 소개, 로그인한 사용자는 역할별 첫 화면으로 바로 이동.
 * 소개하는 기능은 모두 실제 구현된 것만 (docs/api.md·frontend/README.md 기준). 통계·후기·회사 정보는 넣지 않음.
 * 목업의 금액·매장명은 예시 값.
 */
export default function LandingPage() {
  const { status, me } = useAuth();
  usePageMeta(
    `${SERVICE_NAME} — 주문부터 정산까지 한 흐름으로`,
    '고객의 주문·결제부터 사장님의 실시간 주문 처리와 날짜별 정산까지 한 흐름으로 연결하는 배달 주문 서비스입니다.',
    SERVICE_NAME,
  );

  // 새로고침 직후 로그인 복구 중 — 랜딩이 잠깐 보였다가 이동하는 깜빡임 방지
  if (status === 'loading') return <BootScreen />;
  if (status === 'authenticated' && me) return <Navigate to={ROLE_HOME[me.role]} replace />;

  return (
    <div className="landing">
      <a href="#main" className="lskip" onClick={(e) => goToSection(e, 'main')}>
        본문으로 건너뛰기
      </a>
      <LandingHeader />
      <main id="main" tabIndex={-1}>
        <Hero />
        <Intro />
        <Features />
        <Flow />
        <HowItWorks />
        <ClosingCta />
      </main>
      <Footer />
    </div>
  );
}

/* ---------------- Hero ---------------- */
// 첫 화면 순차 진입 — 순서(n)마다 지연이 늘어남 (landing.css .lenter)
const enter = (n: number) => ({ '--enter': n }) as CSSProperties;

function Hero() {
  return (
    <section className="lhero" aria-labelledby="hero-title">
      <div className="lhero__bg" aria-hidden="true" />
      <div className="wrap lhero__in">
        <div className="lhero__copy">
          <span className="lpill lenter" style={enter(0)}>
            <i />
            배달 주문 · 매장 운영 · 정산
          </span>
          <h1 id="hero-title" className="lenter" style={enter(1)}>
            주문부터 정산까지,
            <br />
            <em>한 흐름으로.</em>
          </h1>
          <p className="lhero__sub lenter" style={enter(2)}>
            고객은 메뉴를 담고 주소를 검색해 바로 결제하고, 사장님은 결제된 주문을 실시간으로 받아 처리해요. 배달이 끝난
            주문은 날짜별 정산으로 이어져 입금 예정 금액까지 한눈에 보여요.
          </p>
          <div className="lhero__cta lenter" style={enter(3)}>
            <PrefetchLink to="/signup" page="signup" className="btn btn--lg lbtn">
              회원가입하고 시작하기 <IconArrowRight size={18} />
            </PrefetchLink>
            <PrefetchLink to="/stores" page="stores" className="btn btn--lg btn--ghost lbtn">
              매장 둘러보기
            </PrefetchLink>
          </div>
          <a
            href="#how"
            className="lhero__owner lenter"
            style={enter(4)}
            onClick={(e) => goToSection(e, 'how')}
          >
            사장님이신가요? 입점 절차 보기 <IconArrowRight size={15} />
          </a>
        </div>
        <div className="lhero__visual lenter lenter--visual" style={enter(2)}>
          <HeroVisual />
        </div>
      </div>
      <ul className="wrap lhero__facts" aria-label="핵심 기능">
        <li>
          <IconBell size={18} /> 결제 즉시 사장님께 실시간 알림
        </li>
        <li>
          <IconCheck size={18} /> 다시 눌러도 결제는 한 번만
        </li>
        <li>
          <IconReceipt size={18} /> 날짜별 정산 전표
        </li>
      </ul>
    </section>
  );
}

/* ---------------- 서비스 소개 (문제 → 해결) ---------------- */
const PROBLEMS: { icon: ReactNode; before: string; after: string; detail: string }[] = [
  {
    icon: <IconBell />,
    before: '새 주문을 보려면 화면을 계속 새로고침',
    after: '결제가 끝나면 바로 화면과 소리로 알림',
    detail: '연결이 끊기면 자동으로 다시 연결하고, 그래도 안 되면 30초마다 새 주문을 확인해요.',
  },
  {
    icon: <IconWallet />,
    before: '응답이 끊긴 결제, 다시 누르면 두 번 결제될까 걱정',
    after: '같은 결제 요청은 한 번만 처리',
    detail: '결제마다 고유 키를 붙여 재시도해도 이중 결제가 생기지 않고, 이미 결제된 주문은 최신 상태로 안내해요.',
  },
  {
    icon: <IconReceipt />,
    before: '수수료와 환불이 얼마나 빠졌는지 알기 어려운 입금액',
    after: '매출 − 수수료 − 환불 차감 = 입금 예정',
    detail: '날짜별 전표로 계산 과정을 그대로 보여 주고, 정산 예정·입금 대기·지급 완료 상태로 입금 시점을 알려요.',
  },
];

function Intro() {
  return (
    <section id="intro" className="lsec lintro2" tabIndex={-1} aria-labelledby="intro-title">
      <div className="wrap">
        <Reveal className="lsec__head">
          <span className="leyebrow">서비스 소개</span>
          <h2 id="intro-title">
            배달 주문의 불편한 순간을
            <br />
            하나씩 덜어냈어요
          </h2>
          <p>주문이 들어오는 순간부터 돈이 입금되는 순간까지, 사장님과 고객이 가장 자주 막히는 지점을 먼저 풀었어요.</p>
        </Reveal>
        <ol className="lproblems">
          {PROBLEMS.map((p, i) => (
            <Reveal as="li" key={p.after} className="lproblem" delay={i * 110}>
              <span className="lproblem__icon">{p.icon}</span>
              <div className="lproblem__compare">
                <p className="lproblem__before">
                  <span className="lproblem__tag">기존</span>
                  {p.before}
                </p>
                <p className="lproblem__after">
                  <span className="lproblem__tag lproblem__tag--on">{SERVICE_NAME}</span>
                  {p.after}
                </p>
              </div>
              <p className="lproblem__detail">{p.detail}</p>
            </Reveal>
          ))}
        </ol>
      </div>
    </section>
  );
}

/* ---------------- 주요 기능 (고객 / 사장님 탭) ---------------- */
type Audience = 'customer' | 'owner';

const FEATURES: Record<Audience, { title: string; body: string; points: string[]; mock: ReactNode }[]> = {
  customer: [
    {
      title: '매장을 고르고 메뉴를 담아요',
      body: '매장별 메뉴를 보고 원하는 수량만큼 장바구니에 담아요. 한 주문에는 한 매장의 메뉴만 담기고, 같은 메뉴는 자동으로 합쳐져요.',
      points: ['최종 금액은 서버가 메뉴 가격으로 다시 계산', '주문 당시 메뉴 이름·가격을 그대로 기록'],
      mock: <StoreMenuMock />,
    },
    {
      title: '주소는 검색으로, 결제는 한 번에',
      body: '우편번호 검색으로 도로명 주소를 고르고 상세 주소만 입력하면 돼요. 결제 응답이 끊겨 다시 눌러도 결제는 한 번만 이뤄져요.',
      points: ['다음 우편번호 서비스로 주소 검색', '주소 형식은 화면과 서버에서 두 번 확인'],
      mock: <CheckoutMock />,
    },
    {
      title: '주문 상태와 알림을 한곳에서',
      body: '결제 완료부터 배달 완료까지 주문이 어디쯤인지 확인하고, 결제·배달·환불 소식은 알림으로 받아요. 매장이 수락하기 전이라면 직접 취소할 수 있어요.',
      points: ['결제 내역에서 취소·환불 상태까지 확인', '수락 전 취소 시 결제도 함께 취소'],
      mock: <OrderTrackMock />,
    },
  ],
  owner: [
    {
      title: '새 주문을 놓치지 않아요',
      body: '고객이 결제를 마치면 주문 목록이 바로 바뀌고 알림음이 울려요. 수락 대기 주문을 눌러 수락하고, 배달이 끝나면 배달 완료로 바꾸면 끝이에요.',
      points: ['연결이 끊기면 자동 재연결 · 실패 시 30초 확인', '오늘 매출과 처리 대기 건수를 홈에서 바로'],
      mock: <OwnerOrdersMock />,
    },
    {
      title: '입금될 금액을 미리, 정확하게',
      body: '배달 완료된 주문은 날짜별 정산 전표로 모여요. 매출에서 수수료와 환불 차감을 뺀 입금 예정 금액, 그리고 지금 어느 단계인지 보여 드려요.',
      points: ['수수료는 결제마다 원 단위로 한 번만 반올림', '기간별 정산 내역을 CSV로 내려받기'],
      mock: <SettlementMock />,
    },
    {
      title: '여러 매장도 한 계정으로',
      body: '매장을 여러 개 운영해도 계정 하나면 충분해요. 상단에서 매장을 바꿔 가며 주문·정산·메뉴를 따로 관리해요.',
      points: ['매장별 메뉴 등록', '매장마다 주문과 정산을 나눠서 확인'],
      mock: <StoresMock />,
    },
  ],
};

const TABS: { id: Audience; label: string; icon: ReactNode }[] = [
  { id: 'customer', label: '고객', icon: <IconCart size={18} /> },
  { id: 'owner', label: '사장님', icon: <IconStore size={18} /> },
];

function Features() {
  const [tab, setTab] = useState<Audience>('customer');
  const tabRefs = useRef<Record<Audience, HTMLButtonElement | null>>({ customer: null, owner: null });

  // 탭 키보드 조작 (WAI-ARIA Tabs): 좌우 화살표·Home·End로 이동하면서 바로 선택
  const onKeyDown = (e: KeyboardEvent) => {
    const order = TABS.map((t) => t.id);
    const i = order.indexOf(tab);
    const next =
      e.key === 'ArrowRight' ? order[(i + 1) % order.length]
      : e.key === 'ArrowLeft' ? order[(i - 1 + order.length) % order.length]
      : e.key === 'Home' ? order[0]
      : e.key === 'End' ? order[order.length - 1]
      : null;
    if (!next) return;
    e.preventDefault();
    setTab(next);
    tabRefs.current[next]?.focus();
  };

  return (
    <section id="features" className="lsec lfeat" tabIndex={-1} aria-labelledby="features-title">
      <div className="wrap">
        <Reveal className="lsec__head lsec__head--center">
          <span className="leyebrow">주요 기능</span>
          <h2 id="features-title">누가 쓰든, 필요한 화면이 따로 있어요</h2>
          <p>고객에게는 빠른 주문을, 사장님에게는 놓치지 않는 주문 처리와 투명한 정산을.</p>
        </Reveal>

        <div className="ltabs" role="tablist" aria-label="이용자별 기능" onKeyDown={onKeyDown}>
          {TABS.map((t) => (
            <button
              key={t.id}
              ref={(el) => {
                tabRefs.current[t.id] = el;
              }}
              type="button"
              role="tab"
              id={`tab-${t.id}`}
              aria-selected={tab === t.id}
              aria-controls={`panel-${t.id}`}
              tabIndex={tab === t.id ? 0 : -1}
              className="ltabs__tab"
              onClick={() => setTab(t.id)}
            >
              {t.icon}
              {t.label}
            </button>
          ))}
          <span className={`ltabs__thumb${tab === 'owner' ? ' is-right' : ''}`} aria-hidden="true" />
        </div>

        {TABS.map((t) => (
          <div
            key={t.id}
            role="tabpanel"
            id={`panel-${t.id}`}
            aria-labelledby={`tab-${t.id}`}
            hidden={tab !== t.id}
            className="lrows"
          >
            {FEATURES[t.id].map((f, i) => (
              <article key={f.title} className={`lrow${i % 2 ? ' lrow--flip' : ''}`}>
                <Reveal className="lrow__copy">
                  <span className="lrow__no num">0{i + 1}</span>
                  <h3>{f.title}</h3>
                  <p>{f.body}</p>
                  <ul>
                    {f.points.map((pt) => (
                      <li key={pt}>
                        <IconCheck size={16} /> {pt}
                      </li>
                    ))}
                  </ul>
                </Reveal>
                <Reveal className="lrow__visual" delay={120}>
                  {f.mock}
                </Reveal>
              </article>
            ))}
          </div>
        ))}
      </div>
    </section>
  );
}

/* ---------------- 주문·결제·정산 흐름 ---------------- */
const FLOW: { label: string; who: string; desc: string }[] = [
  { label: '주문 요청', who: '고객', desc: '메뉴와 배달 주소를 담아 주문' },
  { label: '결제 완료', who: '고객', desc: '카드 결제 · 사장님께 즉시 알림' },
  { label: '주문 수락', who: '사장님', desc: '조리 시작 · 이후에는 취소 불가' },
  { label: '배달 완료', who: '사장님', desc: '배달 완료 시각이 정산 기준' },
  { label: '정산', who: '관리자', desc: '정산 예정 → 입금 대기 → 지급 완료' },
];

function Flow() {
  return (
    <section className="lsec lflow" aria-labelledby="flow-title">
      <div className="wrap">
        <Reveal className="lsec__head">
          <span className="leyebrow leyebrow--light">주문 · 결제 · 정산 흐름</span>
          <h2 id="flow-title">주문 하나가 입금되기까지</h2>
          <p>모든 단계는 정해진 순서로만 넘어가요. 결제 전에 수락되거나, 배달이 끝난 주문이 바뀌는 일은 없어요.</p>
        </Reveal>
        <Reveal as="ol" className="lflow__track">
          {FLOW.map((s, i) => (
            <li key={s.label} style={{ '--i': i } as CSSProperties}>
              <span className="lflow__dot num">{i + 1}</span>
              <span className="lflow__who">{s.who}</span>
              <b>{s.label}</b>
              <p>{s.desc}</p>
            </li>
          ))}
        </Reveal>
        <p className="lflow__note">
          배달 완료 후 환불된 주문은 그날 정산에서 차감되고, 차감할 금액이 남으면 다음 정산으로 넘어가요.
        </p>
      </div>
    </section>
  );
}

/* ---------------- 이용 안내 (고객 / 사장님 단계) ---------------- */
const CUSTOMER_STEPS = [
  ['회원가입', '아이디와 비밀번호로 바로 가입해요.'],
  ['매장·메뉴 고르기', '매장을 둘러보고 메뉴를 장바구니에 담아요.'],
  ['주소 검색 후 주문', '우편번호 검색으로 주소를 고르고 상세 주소를 입력해요.'],
  ['카드 결제', '결제가 끝나면 매장에 바로 주문이 전달돼요.'],
  ['주문 확인', '주문 상태와 알림, 결제 내역을 확인해요.'],
];

const OWNER_STEPS = [
  ['회원가입', '먼저 일반 회원으로 가입해요.'],
  ['사장님 전환 신청', '내 정보 화면에서 사장님 전환을 신청해요.'],
  ['관리자 승인', '관리자가 확인하고 승인하면 사장님 화면이 열려요.'],
  ['매장·메뉴 등록', '매장을 만들고 판매할 메뉴를 등록해요.'],
  ['주문 처리와 정산', '실시간으로 주문을 받아 처리하고 정산을 확인해요.'],
];

function Steps({ title, icon, steps, tone }: { title: string; icon: ReactNode; steps: string[][]; tone: 'a' | 'b' }) {
  return (
    <Reveal className={`ltrack ltrack--${tone}`}>
      <h3>
        <span className="ltrack__icon">{icon}</span>
        {title}
      </h3>
      <ol>
        {steps.map(([t, d], i) => (
          <li key={t}>
            <span className="ltrack__no num">{i + 1}</span>
            <div>
              <b>{t}</b>
              <p>{d}</p>
            </div>
          </li>
        ))}
      </ol>
    </Reveal>
  );
}

function HowItWorks() {
  return (
    <section id="how" className="lsec" tabIndex={-1} aria-labelledby="how-title">
      <div className="wrap">
        <Reveal className="lsec__head lsec__head--center">
          <span className="leyebrow">이용 안내</span>
          <h2 id="how-title">이렇게 시작해요</h2>
          <p>모든 회원은 고객으로 가입해요. 사장님은 가입 후 전환을 신청하고, 승인되면 매장을 열 수 있어요.</p>
        </Reveal>
        <div className="ltracks">
          <Steps title="고객이라면" icon={<IconBag size={20} />} steps={CUSTOMER_STEPS} tone="a" />
          <Steps title="사장님이라면" icon={<IconUserCheck size={20} />} steps={OWNER_STEPS} tone="b" />
        </div>
      </div>
    </section>
  );
}

/* ---------------- 마무리 CTA ---------------- */
function ClosingCta() {
  return (
    <section className="lsec lsec--tight" aria-labelledby="cta-title">
      <div className="wrap">
        <Reveal className="lcta">
          <div className="lcta__glow" aria-hidden="true" />
          <div className="lcta__copy">
            <h2 id="cta-title">
              오늘 주문부터
              <br />
              {SERVICE_NAME}로 받아 보세요
            </h2>
            <p>가입은 아이디와 비밀번호면 충분해요. 사장님은 가입 후 내 정보에서 전환을 신청하세요.</p>
          </div>
          <div className="lcta__actions">
            <PrefetchLink to="/signup" page="signup" className="btn btn--lg btn--white lbtn">
              회원가입 <IconArrowRight size={18} />
            </PrefetchLink>
            <PrefetchLink to="/login" page="login" className="btn btn--lg lbtn lcta__login">
              로그인
            </PrefetchLink>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------------- Footer ---------------- */
function Footer() {
  return (
    <footer className="lfoot">
      <div className="wrap lfoot__in">
        <div className="lfoot__brand">
          <Link to="/" className="logo" aria-label={`${SERVICE_NAME} 홈`}>
            <Logo size={30} />
            <b>{SERVICE_NAME}</b>
          </Link>
          <p>고객의 주문과 결제, 사장님의 주문 처리와 정산을 한 흐름으로 잇는 배달 주문 서비스.</p>
        </div>
        <nav className="lfoot__nav" aria-label="바닥글 메뉴">
          <div>
            <h2>서비스</h2>
            <ul>
              {SECTIONS.map((s) => (
                <li key={s.id}>
                  <a href={`#${s.id}`} onClick={(e) => goToSection(e, s.id)}>
                    {s.label}
                  </a>
                </li>
              ))}
            </ul>
          </div>
          <div>
            <h2>시작하기</h2>
            <ul>
              <li>
                <PrefetchLink to="/stores" page="stores">
                  매장 둘러보기
                </PrefetchLink>
              </li>
              <li>
                <PrefetchLink to="/signup" page="signup">
                  회원가입
                </PrefetchLink>
              </li>
              <li>
                <PrefetchLink to="/login" page="login">
                  로그인
                </PrefetchLink>
              </li>
            </ul>
          </div>
        </nav>
      </div>
      <div className="wrap lfoot__bottom">
        <small>© {new Date().getFullYear()} {SERVICE_NAME}</small>
      </div>
    </footer>
  );
}
