// Access Token은 메모리에만 둔다 — localStorage는 XSS로 읽히므로 쓰지 않음 (docs/api.md "인증 토큰")
// 새로고침하면 사라지고, 앱 시작 시 refresh 쿠키로 다시 받는다
type Listener = (token: string | null) => void;

let accessToken: string | null = null;
const listeners = new Set<Listener>();

export const tokenStore = {
  get(): string | null {
    return accessToken;
  },
  set(token: string | null): void {
    if (token === accessToken) return;
    accessToken = token;
    listeners.forEach((l) => l(token));
  },
  clear(): void {
    this.set(null);
  },
  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};
