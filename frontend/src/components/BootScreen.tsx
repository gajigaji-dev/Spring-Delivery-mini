import { Logo } from './Icons';

/** 새로고침 직후 refresh로 로그인을 복구하는 동안 — 로그인 화면이 잠깐 보였다 사라지는 깜빡임 방지 */
export function BootScreen() {
  return (
    <div className="boot" role="status" aria-live="polite">
      <div className="pulse">
        <Logo size={56} />
      </div>
      세션을 확인하고 있어요
    </div>
  );
}
