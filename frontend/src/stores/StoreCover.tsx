import type { CSSProperties } from 'react';

/**
 * 매장 대표 이미지 자리 — API에 이미지가 없어 매장 이름 첫 글자 + 브랜드 톤 무늬로 만든 기본 커버.
 * 같은 매장은 항상 같은 색 (id로 결정) → 목록을 다시 열어도 매장을 알아보기 쉬움. 장식이라 aria-hidden.
 * 이미지 필드가 생기면 이 컴포넌트에서 <img loading="lazy">로 바꾸고 없을 때만 지금 모양을 씀
 */
const TONES = [
  { bg: 'var(--tomato-soft)', fg: 'var(--tomato-700)', dot: 'rgba(207, 62, 30, 0.14)' },
  { bg: 'var(--hold-bg)', fg: 'var(--hold-fg)', dot: 'rgba(138, 86, 0, 0.13)' },
  { bg: 'var(--done-bg)', fg: 'var(--done-fg)', dot: 'rgba(20, 107, 62, 0.12)' },
  { bg: 'var(--prog-bg)', fg: 'var(--prog-fg)', dot: 'rgba(11, 95, 132, 0.12)' },
  { bg: 'var(--oat-2)', fg: 'var(--ink)', dot: 'rgba(27, 26, 23, 0.1)' },
];

export function StoreCover({ id, name }: { id: number; name: string }) {
  const tone = TONES[Math.abs(id) % TONES.length];
  const initial = Array.from(name.trim())[0] ?? '?'; // 이모지·한글 조합도 한 글자로
  const style = { '--cover-bg': tone.bg, '--cover-fg': tone.fg, '--cover-dot': tone.dot } as CSSProperties;
  return (
    <div className="scover" style={style} aria-hidden="true">
      <span className="scover__initial">{initial}</span>
    </div>
  );
}
