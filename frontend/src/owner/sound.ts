/*
 * 새 주문 알림 소리 (#76) — 음원 파일 없이 Web Audio로 짧은 두 음을 만듦
 *
 * 브라우저 자동 재생 정책: 사용자가 페이지에서 클릭 등을 하기 전에는 AudioContext가 suspended로 시작해 소리가 나지 않음.
 * → '알림 소리 켜기' 버튼 클릭 안에서 AudioContext를 만들고 resume해야 이후 이벤트에서 소리를 낼 수 있음.
 * 새로고침하면 다시 클릭이 필요하므로 켜짐 여부를 저장해 두지 않음 (저장해도 소리가 나지 않아 화면 상태만 틀어짐)
 */

type AudioContextCtor = typeof AudioContext;

let context: AudioContext | null = null;

function audioContextCtor(): AudioContextCtor | null {
  if (typeof window === 'undefined') return null;
  // 오래된 Safari는 접두사 이름만 있음
  const w = window as unknown as { AudioContext?: AudioContextCtor; webkitAudioContext?: AudioContextCtor };
  return w.AudioContext ?? w.webkitAudioContext ?? null;
}

export function soundSupported(): boolean {
  return audioContextCtor() !== null;
}

function chime(ctx: AudioContext): void {
  const start = ctx.currentTime;
  [880, 1320].forEach((freq, i) => {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    const t = start + i * 0.18;
    osc.type = 'sine';
    osc.frequency.value = freq;
    // 바로 큰 소리가 나면 '틱' 잡음이 생겨 짧게 올렸다가 내림
    gain.gain.setValueAtTime(0, t);
    gain.gain.linearRampToValueAtTime(0.25, t + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.001, t + 0.3);
    osc.connect(gain).connect(ctx.destination);
    osc.start(t);
    osc.stop(t + 0.32);
  });
}

/** 클릭 핸들러 안에서만 호출 — 켜졌는지 확인용으로 한 번 울림. 브라우저가 막으면 false */
export async function enableSound(): Promise<boolean> {
  const Ctor = audioContextCtor();
  if (!Ctor) return false;
  context ??= new Ctor();
  try {
    await context.resume();
  } catch {
    return false;
  }
  if (context.state !== 'running') return false;
  chime(context);
  return true;
}

export function disableSound(): void {
  void context?.suspend();
}

/** 켜져 있을 때만 울림 — 켜지 않았으면 조용히 넘어감 (토스트는 따로 뜸) */
export function playNewOrderSound(): void {
  if (context?.state === 'running') chime(context);
}
