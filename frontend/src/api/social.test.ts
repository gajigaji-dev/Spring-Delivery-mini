import { describe, expect, it } from 'vitest';
import { messageForCode } from './errorMessages';
import { parseOAuthCallback, socialAuthorizeUrl } from './social';

describe('parseOAuthCallback', () => {
  it('서버가 보내는 결과를 구분한다', () => {
    expect(parseOAuthCallback('?result=login')).toEqual({ kind: 'login' });
    expect(parseOAuthCallback('?result=linked&provider=kakao')).toEqual({ kind: 'linked', provider: 'kakao' });
    expect(parseOAuthCallback('?error=OAUTH2_CANCELLED')).toEqual({ kind: 'error', code: 'OAUTH2_CANCELLED' });
  });

  it('모르는 값·형식이 이상한 오류 코드는 일반 실패로 처리한다', () => {
    expect(parseOAuthCallback('')).toEqual({ kind: 'error', code: 'OAUTH2_FAILED' });
    expect(parseOAuthCallback('?error=<script>')).toEqual({ kind: 'error', code: 'OAUTH2_FAILED' });
  });
});

describe('소셜 로그인 문구·주소', () => {
  it('오류 코드별 안내, 모르는 코드는 일반 실패 문구', () => {
    expect(messageForCode('OAUTH2_CANCELLED')).toBe('소셜 로그인을 취소했습니다.');
    expect(messageForCode('SOMETHING_NEW')).toBe(messageForCode('OAUTH2_FAILED'));
  });

  it('연결 모드는 mode=link를 붙이고, 로그인은 붙이지 않는다', () => {
    expect(socialAuthorizeUrl('kakao')).toMatch(/\/oauth2\/authorization\/kakao$/);
    expect(socialAuthorizeUrl('google', 'link')).toMatch(/\/oauth2\/authorization\/google\?mode=link$/);
  });
});
