export interface DemoAccount {
  label: string;
  role: string;
  username: string;
  password: string;
}

const LABEL: Record<string, string> = { OWNER: '사장님', CUSTOMER: '손님', ADMIN: '관리자' };

/**
 * 로그인 화면 "테스트 계정으로 채우기" — VITE_DEMO_ACCOUNTS가 있을 때만 보임
 * 형식: 역할:아이디:비밀번호를 쉼표로 (예: OWNER:owner1:password123,ADMIN:admin1:password123)
 * 계정 정보를 소스에 넣지 않기 위해 환경 변수로만 받는다 — 운영 빌드에는 설정하지 않음
 */
export function parseDemoAccounts(raw: string | undefined): DemoAccount[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((part) => part.trim().split(':'))
    .filter((p) => p.length === 3 && p.every(Boolean))
    .map(([role, username, password]) => ({
      role: role.toUpperCase(),
      label: LABEL[role.toUpperCase()] ?? role,
      username,
      password,
    }));
}

export const demoAccounts = parseDemoAccounts(import.meta.env.VITE_DEMO_ACCOUNTS as string | undefined);
