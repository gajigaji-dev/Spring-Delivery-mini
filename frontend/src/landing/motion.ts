import { useEffect, useRef, useState, type RefObject } from 'react';

/**
 * 랜딩 애니메이션 도구 — 라이브러리(Framer Motion 등) 없이 IntersectionObserver + CSS 전환만 사용.
 * 랜딩 한 화면을 위해 의존성을 늘리지 않기 위함 (등장·순차 등장·카운트업 정도는 CSS로 충분).
 * 동작 줄이기(prefers-reduced-motion)면 처음부터 최종 상태로 보여 줌.
 */

export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
}

// 요소마다 관찰자를 만들지 않고 하나를 공유 — 탭 전환으로 나중에 나타나는 요소도 같은 관찰자에 등록
type Listener = (visible: boolean) => void;
let observer: IntersectionObserver | null = null;
const listeners = new WeakMap<Element, Listener>();

function sharedObserver(): IntersectionObserver {
  observer ??= new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          listeners.get(entry.target)?.(true);
          observer?.unobserve(entry.target); // 한 번 나타나면 끝 (스크롤을 올려도 다시 숨기지 않음)
        }
      }
    },
    // 화면 아래쪽 10%에 닿기 전에 시작 → 사용자가 빈 자리를 보지 않음
    { rootMargin: '0px 0px -10% 0px', threshold: 0.12 },
  );
  return observer;
}

/** 요소가 화면에 들어왔는지 (한 번 true가 되면 유지) */
export function useInView<T extends Element>(): [RefObject<T | null>, boolean] {
  const ref = useRef<T>(null);
  const [visible, setVisible] = useState(() => prefersReducedMotion() || typeof IntersectionObserver === 'undefined');

  useEffect(() => {
    const el = ref.current;
    if (visible || !el) return;
    const io = sharedObserver();
    listeners.set(el, setVisible);
    io.observe(el);
    return () => {
      io.unobserve(el);
      listeners.delete(el);
    };
  }, [visible]);

  return [ref, visible];
}

/** 0 → target 카운트업 (easeOutCubic). start가 true가 될 때 한 번 */
export function useCountUp(target: number, start: boolean, durationMs = 1100): number {
  const [value, setValue] = useState(() => (prefersReducedMotion() ? target : 0));

  useEffect(() => {
    if (!start) return;
    if (prefersReducedMotion()) {
      setValue(target);
      return;
    }
    let frame = 0;
    const begin = performance.now();
    const tick = (now: number) => {
      const t = Math.min(1, (now - begin) / durationMs);
      setValue(Math.round(target * easeOutCubic(t)));
      if (t < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [start, target, durationMs]);

  return value;
}

export function easeOutCubic(t: number): number {
  return 1 - Math.pow(1 - t, 3);
}

/**
 * 화면별 title·description. 떠날 때는 leaveTitle로 바꿈 —
 * index.html의 기본 title이 랜딩 문구(검색엔진이 `/`에서 처음 받는 HTML)라 "이전 값 복원"으로는 다른 화면에 랜딩 문구가 남음
 */
export function usePageMeta(title: string, description: string, leaveTitle: string): void {
  useEffect(() => {
    const meta = document.querySelector<HTMLMetaElement>('meta[name="description"]');
    const prevDescription = meta?.content;
    document.title = title;
    if (meta) meta.content = description;
    return () => {
      document.title = leaveTitle;
      if (meta && prevDescription !== undefined) meta.content = prevDescription;
    };
  }, [title, description, leaveTitle]);
}
