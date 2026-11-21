import { useState } from 'react';
import { socialApi, socialAuthorizeUrl, socialLabel } from '../api/social';
import { useAsync } from '../hooks/useAsync';
import { SocialLogo } from './SocialLogo';

// 공급자별 버튼 모양·문구 — 각 공급자 로그인 버튼 가이드(카카오 노란색, 구글 흰 바탕·'Google' 영문 표기)를 따름
const BRAND: Record<string, { className: string; login: string; signup: string }> = {
  kakao: { className: 'btn--kakao', login: '카카오 로그인', signup: '카카오로 시작하기' },
  google: { className: 'btn--google', login: 'Google 계정으로 로그인', signup: 'Google로 시작하기' },
};
// 국내 사용자가 많은 카카오를 먼저 — 서버 목록 순서는 정해져 있지 않음
const ORDER = ['kakao', 'google'];
const rank = (p: string) => (ORDER.indexOf(p) === -1 ? ORDER.length : ORDER.indexOf(p));

/**
 * 소셜 로그인 버튼 (V22) — 서버에 키가 설정된 공급자만 표시, 목록을 못 받으면 아무것도 그리지 않음
 * 처음 로그인하면 고객(CUSTOMER)으로 자동 가입. 기존 회원은 로그인 후 내 정보에서 연결
 * 가입 화면도 같은 흐름(서버 기준 로그인 = 가입)이라 문구만 바꿔 재사용
 */
export function SocialLoginButtons({ variant = 'login' }: { variant?: 'login' | 'signup' }) {
  const { data: providers } = useAsync(() => socialApi.providers(), []);
  const [leaving, setLeaving] = useState(false);
  if (!providers || providers.length === 0) return null;

  return (
    <div style={{ marginTop: 24, display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="divider-label">소셜 계정으로 계속하기</div>
      {[...providers].sort((a, b) => rank(a) - rank(b)).map((p) => (
        <button
          key={p}
          type="button"
          className={`btn btn--lg btn--block btn--social ${BRAND[p]?.className ?? 'btn--ghost'}`}
          disabled={leaving}
          onClick={() => {
            // 페이지 전체 이동 — 메모리의 Access Token은 사라지지만 돌아온 뒤 refresh로 복구
            setLeaving(true);
            window.location.assign(socialAuthorizeUrl(p));
          }}
        >
          <span className="btn--social__logo">
            <SocialLogo provider={p} />
          </span>
          {BRAND[p]?.[variant] ?? `${socialLabel(p)}로 ${variant === 'signup' ? '시작하기' : '로그인'}`}
        </button>
      ))}
      <p className="muted" style={{ fontSize: 13, margin: 0 }}>
        {variant === 'signup'
          ? '아이디·비밀번호 없이 고객 계정이 만들어집니다. 이미 가입한 소셜 계정이면 바로 로그인돼요.'
          : '처음이면 고객 계정이 만들어집니다. 기존 계정은 로그인 후 내 정보에서 연결하세요.'}
      </p>
    </div>
  );
}
