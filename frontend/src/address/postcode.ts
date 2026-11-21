/**
 * 카카오(구 다음) 우편번호 서비스 (#77) — https://postcode.map.kakao.com/guide
 * 키 발급 없이 쓰는 공개 스크립트. 장바구니에서 '주소 검색'을 누를 때 처음 한 번만 불러옴
 * (모든 화면의 첫 로딩에 외부 스크립트를 넣지 않도록).
 */
// 쿼리 파라미터를 붙이지 않음 — 현재 스크립트는 파라미터가 있으면 "허용되지 않는 파라미터" 오류를 띄움
// (예전 가이드의 ?autoload=false는 없어짐). 스크립트가 실행되면 kakao.Postcode가 바로 생기므로 onload에서 꺼내 씀
const SCRIPT_URL = 'https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js';
const LOAD_TIMEOUT_MS = 10_000;

/** oncomplete로 받는 값 중 쓰는 것만 */
export interface PostcodeData {
  zonecode: string; // 5자리 우편번호
  roadAddress: string;
  autoRoadAddress: string; // 지번 주소를 골랐을 때 대응하는 도로명 주소
  userSelectedType: 'R' | 'J';
  bname: string; // 법정동·법정리
  buildingName: string;
  apartment: 'Y' | 'N';
}

interface PostcodeInstance {
  embed(element: HTMLElement, options?: { autoClose?: boolean }): void;
}

interface PostcodeConstructor {
  new (options: { oncomplete: (data: PostcodeData) => void; width?: string; height?: string }): PostcodeInstance;
}

// 현재 이름은 kakao, daum은 예전 코드 호환용 별칭 (스크립트가 둘을 같은 객체로 맞춤)
declare global {
  interface Window {
    kakao?: { Postcode?: PostcodeConstructor };
    daum?: { Postcode?: PostcodeConstructor };
  }
}

function loadedPostcode(): PostcodeConstructor | undefined {
  return window.kakao?.Postcode ?? window.daum?.Postcode;
}

let loading: Promise<PostcodeConstructor> | null = null;

/** 스크립트를 한 번만 불러옴. 실패하면 다음 호출에서 다시 시도 */
export function loadPostcode(): Promise<PostcodeConstructor> {
  const ready = loadedPostcode();
  if (ready) return Promise.resolve(ready);
  if (loading) return loading;

  loading = new Promise<PostcodeConstructor>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = SCRIPT_URL;
    script.async = true;
    const timer = setTimeout(() => fail(new Error('timeout')), LOAD_TIMEOUT_MS);
    const fail = (error: Error) => {
      clearTimeout(timer);
      script.remove();
      loading = null;
      reject(error);
    };
    script.onload = () => {
      const Postcode = loadedPostcode();
      if (!Postcode) {
        fail(new Error('missing'));
        return;
      }
      clearTimeout(timer);
      resolve(Postcode);
    };
    script.onerror = () => fail(new Error('load error'));
    document.head.appendChild(script);
  });
  return loading;
}

/**
 * 검색 결과 → 저장할 도로명 주소.
 * 지번을 골라도 도로명으로 저장(서버·배달은 도로명 기준), 아파트·건물명은 공식 가이드처럼 괄호로 덧붙임.
 */
export function toRoadAddress(data: PostcodeData): string {
  const road = data.roadAddress || data.autoRoadAddress;
  const extras: string[] = [];
  // 법정동은 '동·로·가'로 끝날 때만 (리 단위는 주소에 이미 포함)
  if (data.bname && /[동로가]$/.test(data.bname)) extras.push(data.bname);
  if (data.buildingName && data.apartment === 'Y') extras.push(data.buildingName);
  return extras.length > 0 ? `${road} (${extras.join(', ')})` : road;
}
