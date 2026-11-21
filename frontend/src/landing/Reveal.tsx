import type { CSSProperties, ElementType, ReactNode } from 'react';
import { useInView } from './motion';

interface Props {
  children: ReactNode;
  as?: ElementType;
  className?: string;
  /** 순차 등장용 지연(ms) — 같은 묶음 안에서 80~120ms씩 늘림 */
  delay?: number;
  id?: string;
}

/** 화면에 들어오면 아래에서 살짝 떠오르며 나타남 (스타일은 landing.css `.reveal`) */
export function Reveal({ children, as: Tag = 'div', className = '', delay = 0, id }: Props) {
  const [ref, visible] = useInView<HTMLElement>();
  const style = delay ? ({ '--reveal-delay': `${delay}ms` } as CSSProperties) : undefined;
  return (
    <Tag ref={ref} id={id} className={`reveal${visible ? ' is-in' : ''}${className ? ` ${className}` : ''}`} style={style}>
      {children}
    </Tag>
  );
}
