import type { ReactNode } from 'react';

// 아이콘: 24 그리드, 1.8 스트로크, 둥근 끝 (디자인 형태 규칙). 장식용이라 모두 aria-hidden
function Stroke({ children, size = 20, width = 1.8 }: { children: ReactNode; size?: number; width?: number }) {
  return (
    <svg
      className="ico"
      style={{ width: size, height: size }}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={width}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

type P = { size?: number };

export function Logo({ size = 32 }: P) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="9" style={{ fill: 'var(--tomato)' }} />
      <path d="M9.5 20.5a6.5 6.5 0 0 1 13 0" stroke="#fff" strokeWidth="2.2" fill="none" strokeLinecap="round" />
      <path d="M7.5 21.5h17" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" />
      <circle cx="16" cy="12" r="1.6" fill="#fff" />
    </svg>
  );
}

export const IconHome = (p: P) => (
  <Stroke {...p}>
    <path d="M4 11 12 4l8 7v9h-5v-6H9v6H4v-9Z" />
  </Stroke>
);
export const IconBag = (p: P) => (
  <Stroke {...p}>
    <path d="M6 7h12l-1 13H7L6 7Z" />
    <path d="M9 7a3 3 0 0 1 6 0" />
  </Stroke>
);
export const IconWallet = (p: P) => (
  <Stroke {...p}>
    <path d="M4 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7Z" />
    <path d="M4 7l11-3v3" />
    <circle cx="16" cy="13.5" r="1.2" />
  </Stroke>
);
export const IconReceipt = (p: P) => (
  <Stroke {...p}>
    <path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3Z" />
    <path d="M9 8h6M9 12h6" />
  </Stroke>
);
export const IconStore = (p: P) => (
  <Stroke {...p}>
    <path d="M4 10l1.5-5h13L20 10" />
    <path d="M5 10v9h14v-9" />
    <path d="M4 10a2.7 2.7 0 0 0 5.3 0 2.7 2.7 0 0 0 5.4 0 2.7 2.7 0 0 0 5.3 0" />
  </Stroke>
);
export const IconCart = (p: P) => (
  <Stroke {...p}>
    <path d="M3 4h2l2.2 11h10.6L20 7H6.2" />
    <circle cx="9" cy="19.5" r="1.3" />
    <circle cx="17" cy="19.5" r="1.3" />
  </Stroke>
);
export const IconUserCheck = (p: P) => (
  <Stroke {...p}>
    <circle cx="10" cy="8" r="3.5" />
    <path d="M3.5 20a6.5 6.5 0 0 1 11.5-4" />
    <path d="m15 18 2 2 4-4.5" />
  </Stroke>
);
export const IconPulse = (p: P) => (
  <Stroke {...p}>
    <path d="M3 12h4l2.5-6 5 12 2.5-6h4" />
  </Stroke>
);
export const IconRefresh = (p: P) => (
  <Stroke {...p}>
    <path d="M20 11a8 8 0 1 0-2.3 5.7" />
    <path d="M20 5v6h-6" />
  </Stroke>
);
export const IconDownload = (p: P) => (
  <Stroke {...p}>
    <path d="M12 4v11M7 10l5 5 5-5" />
    <path d="M5 20h14" />
  </Stroke>
);
export const IconArrowRight = (p: P) => (
  <Stroke {...p} width={2}>
    <path d="M5 12h14M13 6l6 6-6 6" />
  </Stroke>
);
export const IconChevronDown = (p: P) => (
  <Stroke size={16} {...p} width={2}>
    <path d="m6 9 6 6 6-6" />
  </Stroke>
);
export const IconCheck = (p: P) => (
  <Stroke {...p} width={2.2}>
    <path d="m5 12 5 5 9-10" />
  </Stroke>
);
export const IconAlert = (p: P) => (
  <Stroke {...p} width={2}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7.5v5.5M12 16.5v.01" />
  </Stroke>
);
export const IconInfo = (p: P) => (
  <Stroke {...p} width={2}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 11v5.5M12 7.5v.01" />
  </Stroke>
);
export const IconLogout = (p: P) => (
  <Stroke {...p}>
    <path d="M15 4h4v16h-4" />
    <path d="M10 8l-4 4 4 4M6 12h10" />
  </Stroke>
);
export const IconBell = (p: P) => (
  <Stroke {...p}>
    <path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15L6 16Z" />
    <path d="M10 20.5a2 2 0 0 0 4 0" />
  </Stroke>
);
export const IconUser = (p: P) => (
  <Stroke {...p}>
    <circle cx="12" cy="8" r="3.5" />
    <path d="M5 20a7 7 0 0 1 14 0" />
  </Stroke>
);
