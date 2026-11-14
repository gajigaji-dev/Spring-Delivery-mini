// 목록 API 부하 테스트 (#40) — 실행 방법·결과는 docs/performance.md
// 한 번에 API 하나만 측정 (TARGET) → API별 응답 시간·처리량이 서로 섞이지 않도록
//   TARGET: stores | menus | orders-customer | orders-owner | store-orders
//   QUERY : 쿼리 문자열 (예: "?page=100&size=20"), 기본은 파라미터 없이 호출
//   VUS / DURATION: 동시 사용자 수 / 측정 시간 (기본 20명, 30초)
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:18080';
const TARGET = __ENV.TARGET || 'stores';
const QUERY = __ENV.QUERY || '';
const PASSWORD = __ENV.PERF_PASSWORD || 'perf-password-1!'; // perf/seed.sql의 로그인 계정 비밀번호
const DURATION = __ENV.DURATION || '30s';

// 로그인(setup) 요청이 통계에 섞이지 않도록 목록 요청만 따로 집계
const listDuration = new Trend('list_duration', true);
const listErrors = new Rate('list_errors');
const listRequests = new Counter('list_requests');
const listBytes = new Trend('list_body_bytes');

// 대상별 경로와 로그인 계정 (공개 API는 로그인하지 않음)
const TARGETS = {
  stores: { path: '/api/stores' },
  menus: { path: '/api/menus' },
  'orders-customer': { path: '/api/orders', username: 'perf_customer' },
  'orders-owner': { path: '/api/orders', username: 'perf_owner' },
  'store-orders': { path: '/api/stores/1/orders', username: 'perf_owner' },
};

export const options = {
  scenarios: {
    list: { executor: 'constant-vus', vus: Number(__ENV.VUS || 20), duration: DURATION },
  },
  // 느린 응답이 있어도 측정이 중간에 끊기지 않도록 넉넉하게 (개선 전 전체 조회 대비)
  setupTimeout: '60s',
};

export function setup() {
  const target = TARGETS[TARGET];
  if (!target) {
    throw new Error(`알 수 없는 TARGET: ${TARGET}`);
  }
  if (!target.username) {
    return { headers: {} };
  }
  const res = http.post(`${BASE_URL}/api/users/login`,
    JSON.stringify({ username: target.username, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) {
    throw new Error(`로그인 실패 (${res.status}) — perf/seed.sql을 실행했는지 확인`);
  }
  return { headers: { Authorization: `Bearer ${res.json('accessToken')}` } };
}

export default function (data) {
  const res = http.get(`${BASE_URL}${TARGETS[TARGET].path}${QUERY}`,
    { headers: data.headers, timeout: '120s' });
  const ok = check(res, { '200 응답': (r) => r.status === 200 });
  listDuration.add(res.timings.duration);
  listErrors.add(!ok);
  listRequests.add(1);
  listBytes.add(res.body ? res.body.length : 0);
}

// 문서에 옮기기 쉽게 한 줄 요약만 출력: 평균·p95(ms), 처리량(req/s), 오류율(%), 응답 크기(KB)
export function handleSummary(data) {
  const m = data.metrics;
  const d = m.list_duration.values;
  const line = [
    `target=${TARGET}${QUERY}`,
    `requests=${m.list_requests.values.count}`,
    `avg_ms=${d.avg.toFixed(1)}`,
    `p95_ms=${d['p(95)'].toFixed(1)}`,
    `max_ms=${d.max.toFixed(1)}`,
    `rps=${m.list_requests.values.rate.toFixed(1)}`,
    `error_pct=${(m.list_errors.values.rate * 100).toFixed(2)}`,
    `body_kb=${(m.list_body_bytes.values.avg / 1024).toFixed(1)}`,
  ].join(' ');
  return { stdout: `RESULT ${line}\n` };
}
