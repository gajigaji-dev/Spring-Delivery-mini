// 주문·결제 쓰기 경로 부하 테스트 (#68) — 실행 방법·결과는 docs/performance.md 11장
//
// SCENARIO=load (기본): 고객 로그인 → 반복(주문 생성 → 결제)
//   VUS: 동시 사용자 수 (기본 10) — 사용자마다 계정을 따로 만들어 로그인 (계정 1개 공유 시 생기지 않는 경합까지 재현)
//   WARMUP / DURATION: 워밍업 시간(통계 제외, 기본 10s) / 측정 시간(기본 60s)
//   IDEMPOTENCY: on(기본) = 결제마다 새 Idempotency-Key / off = 헤더 없이 결제 (Redis 단계 비용 비교용)
// SCENARIO=idempotency: 같은 Idempotency-Key로 동시 결제 → 결제가 1건만 생기는지 확인
//   CONCURRENCY: 같은 키로 동시에 보내는 요청 수 (기본 20) / ROUNDS: 주문 수 = 반복 횟수 (기본 20)
//
// 계정·주문은 setup에서 API로 만듦 (실행마다 새 계정 → 이전 실행의 멱등성 키·결제와 섞이지 않음)
// 메뉴는 기존 데이터(perf/seed.sql)의 메뉴를 쓰고, 없으면 점주·매장·메뉴를 API로 만듦
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const SCENARIO = __ENV.SCENARIO || 'load';
const VUS = Number(__ENV.VUS || 10);
const WARMUP = __ENV.WARMUP || '10s';
const DURATION = __ENV.DURATION || '60s';
const USE_KEY = (__ENV.IDEMPOTENCY || 'on') !== 'off';
const CONCURRENCY = Number(__ENV.CONCURRENCY || 20);
const ROUNDS = Number(__ENV.ROUNDS || 20);
const ROUND_GAP_MS = 1000; // 라운드 사이 간격 — 앞 라운드 응답이 다음 라운드와 겹치지 않도록
const PASSWORD = 'perf-password-1!';
// 실행마다 다른 접두사 (아이디 4~20자, 멱등성 키 영문·숫자·-·_ 64자 이하)
const RUN_ID = __ENV.RUN_ID || Date.now().toString(36);
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 워밍업·setup 요청이 섞이지 않도록 측정 구간만 따로 집계
const orderDuration = new Trend('order_duration', true);
const paymentDuration = new Trend('payment_duration', true);
const flowDuration = new Trend('flow_duration', true);
const flowErrors = new Rate('flow_errors');
const flows = new Counter('flows');
const loginDuration = new Trend('login_duration', true);
// 멱등성 시나리오 결과
const idemCreated = new Counter('idem_created');    // 201 + 재응답 아님 = 실제 결제
const idemReplayed = new Counter('idem_replayed');  // 201 + Idempotent-Replayed: true
const idemConflict = new Counter('idem_conflict');  // 409 (같은 키 처리 중)
const idemOther = new Counter('idem_other');        // 그 밖의 응답 = 오류
const idemMismatch = new Counter('idem_mismatch');  // 응답 결제 id가 주문의 실제 결제와 다름
const idemPaymentsFound = new Counter('idem_payments_found'); // 끝난 뒤 조회한 결제 수
const idemDuplicateOrders = new Counter('idem_duplicate_orders'); // 결제가 2건 이상인 주문 수

const scenarios = SCENARIO === 'idempotency'
  ? {
    // VU마다 라운드 수만큼 반복, 라운드 r은 모든 VU가 같은 시각에 같은 키로 요청
    idempotency: {
      executor: 'per-vu-iterations', vus: CONCURRENCY, iterations: ROUNDS,
      maxDuration: `${ROUNDS * 2 + 60}s`,
    },
  }
  : {
    // 워밍업 중인 반복이 측정 구간에 넘어오지 않도록 gracefulStop 0 (끊긴 주문은 결제되지 않은 채 남을 뿐)
    warmup: { executor: 'constant-vus', vus: VUS, duration: WARMUP, gracefulStop: '0s' },
    measure: { executor: 'constant-vus', vus: VUS, duration: DURATION, startTime: WARMUP },
  };

export const options = { scenarios, setupTimeout: '300s', teardownTimeout: '120s' };

function post(path, body, token, extraHeaders, name) {
  const headers = Object.assign({}, JSON_HEADERS, extraHeaders || {});
  if (token) headers.Authorization = `Bearer ${token}`;
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers, tags: { name: name || path } });
}

function get(path, token, name) {
  return http.get(`${BASE_URL}${path}`, { headers: { Authorization: `Bearer ${token}` }, tags: { name: name || path } });
}

function mustStatus(res, status, what) {
  if (res.status !== status) {
    throw new Error(`${what} 실패 (${res.status}): ${String(res.body).slice(0, 200)}`);
  }
  return res;
}

function signupAndLogin(username, role) {
  mustStatus(post('/api/users/signup', { username, password: PASSWORD, role }), 201, `회원가입 ${username}`);
  const res = mustStatus(post('/api/users/login', { username, password: PASSWORD }), 200, `로그인 ${username}`);
  loginDuration.add(res.timings.duration);
  return res.json('accessToken');
}

// 메뉴가 2개 이상인 매장들 — 주문 1건에 같은 매장 메뉴 2개를 담기 위해
function loadStores() {
  const res = mustStatus(http.get(`${BASE_URL}/api/menus?page=0&size=100`), 200, '메뉴 조회');
  const byStore = {};
  for (const m of res.json()) {
    (byStore[m.storeId] = byStore[m.storeId] || []).push(m.id);
  }
  const stores = Object.values(byStore).filter((ids) => ids.length >= 2);
  if (stores.length > 0) return stores;

  // 빈 DB: 점주·매장 1곳·메뉴 2개를 만듦
  const ownerToken = signupAndLogin(`pwo${RUN_ID}`, 'OWNER');
  const store = mustStatus(post('/api/stores', { name: `부하 테스트 ${RUN_ID}` }, ownerToken), 201, '매장 생성');
  const menuIds = [1, 2].map((i) => mustStatus(post('/api/menus',
    { storeId: store.json('id'), name: `메뉴 ${i}`, price: 10000 + i * 1000 }, ownerToken), 201, '메뉴 생성').json('id'));
  return [menuIds];
}

function createOrder(token, stores, seq) {
  const menus = stores[seq % stores.length];
  return post('/api/orders', {
    items: [{ menuId: menus[0], quantity: 1 + (seq % 3) }, { menuId: menus[1], quantity: 1 }],
    zipCode: '06236',
    roadAddress: `서울 강남구 테헤란로 ${1 + (seq % 500)}`,
    detailAddress: `${seq}호`,
  }, token, null, 'POST /api/orders');
}

export function setup() {
  const stores = loadStores();
  if (SCENARIO === 'idempotency') {
    const token = signupAndLogin(`pwi${RUN_ID}`, 'CUSTOMER');
    const orders = [];
    for (let r = 0; r < ROUNDS; r++) {
      const res = mustStatus(createOrder(token, stores, r), 201, '주문 생성');
      orders.push({ id: res.json('id'), totalPrice: res.json('totalPrice') });
    }
    // 모든 VU가 준비된 뒤 첫 라운드를 시작하도록 여유를 둠
    return { token, orders, startAt: Date.now() + 3000 };
  }
  // VU 1명당 계정 1개 (워밍업·측정 시나리오가 같은 VU 번호를 쓰므로 VUS개면 충분, 넉넉하게 2배)
  const tokens = [];
  for (let i = 0; i < VUS * 2; i++) {
    tokens.push(signupAndLogin(`pwc${RUN_ID}_${i}`, 'CUSTOMER'));
  }
  return { tokens, stores };
}

function loadIteration(data) {
  const measuring = exec.scenario.name === 'measure';
  const token = data.tokens[(exec.vu.idInTest - 1) % data.tokens.length];
  const seq = exec.vu.idInTest * 100000 + exec.vu.iterationInInstance;

  const order = createOrder(token, data.stores, seq);
  const orderOk = check(order, { '주문 201': (r) => r.status === 201 });
  let payOk = false;
  let pay = null;
  if (orderOk) {
    const headers = USE_KEY ? { 'Idempotency-Key': `${RUN_ID}-${exec.vu.idInTest}-${exec.vu.iterationInInstance}` } : {};
    pay = post('/api/payments', { orderId: order.json('id'), paymentMethod: 'CARD' }, token, headers, 'POST /api/payments');
    payOk = check(pay, {
      '결제 201': (r) => r.status === 201,
      '새 결제 (재응답 아님)': (r) => r.headers['Idempotent-Replayed'] === undefined,
      '결제 금액 = 주문 총액': (r) => r.status === 201 && r.json('amount') === order.json('totalPrice'),
    });
  }
  if (!measuring) return;
  orderDuration.add(order.timings.duration);
  if (pay) paymentDuration.add(pay.timings.duration);
  if (orderOk && payOk) flowDuration.add(order.timings.duration + pay.timings.duration);
  flowErrors.add(!(orderOk && payOk));
  flows.add(1);
}

function idempotencyIteration(data) {
  const round = exec.vu.iterationInInstance;
  const order = data.orders[round];
  // 라운드 시작 시각까지 대기 → 모든 VU가 거의 같은 시각에 요청
  const wait = data.startAt + round * ROUND_GAP_MS - Date.now();
  if (wait > 0) sleep(wait / 1000);

  const res = post('/api/payments', { orderId: order.id, paymentMethod: 'CARD' }, data.token,
    { 'Idempotency-Key': `${RUN_ID}-idem-${round}` }, 'POST /api/payments (same key)');
  if (res.status === 201) {
    if (res.headers['Idempotent-Replayed'] === 'true') idemReplayed.add(1); else idemCreated.add(1);
    // 재응답이든 새 결제든 응답의 결제 id가 주문에 실제로 연결된 결제와 같아야 함
    const detail = get(`/api/orders/${order.id}`, data.token, 'GET /api/orders/{id}');
    const same = detail.status === 200 && detail.json('payment.id') === res.json('id')
      && res.json('orderId') === order.id && res.json('amount') === order.totalPrice;
    if (!check(res, { '응답 결제 = 주문의 실제 결제': () => same })) idemMismatch.add(1);
  } else if (res.status === 409) {
    idemConflict.add(1);
  } else {
    idemOther.add(1);
    console.error(`예상하지 못한 응답 round=${round} status=${res.status} body=${String(res.body).slice(0, 200)}`);
  }
}

export default function (data) {
  if (SCENARIO === 'idempotency') idempotencyIteration(data); else loadIteration(data);
}

// 멱등성 시나리오: 끝난 뒤 이 고객의 결제를 다시 조회해 주문당 1건인지 확인 (DB 기준 재확인은 docs 11장의 SQL)
export function teardown(data) {
  if (SCENARIO !== 'idempotency') return;
  const res = mustStatus(get(`/api/payments?page=0&size=100`, data.token, 'GET /api/payments'), 200, '결제 목록 조회');
  const perOrder = {};
  for (const p of res.json()) perOrder[p.orderId] = (perOrder[p.orderId] || 0) + 1;
  idemPaymentsFound.add(res.json().length);
  idemDuplicateOrders.add(Object.values(perOrder).filter((n) => n > 1).length);
}

function seconds(d) {
  const n = parseFloat(d);
  return d.endsWith('m') ? n * 60 : n;
}

function value(m, name, key) {
  return m[name] ? m[name].values[key] : 0;
}

// 문서에 옮기기 쉽게 한 줄 요약만 출력
export function handleSummary(data) {
  const m = data.metrics;
  let line;
  if (SCENARIO === 'idempotency') {
    line = [
      `scenario=idempotency rounds=${ROUNDS} concurrency=${CONCURRENCY}`,
      `requests=${ROUNDS * CONCURRENCY}`,
      `created=${value(m, 'idem_created', 'count')}`,
      `replayed=${value(m, 'idem_replayed', 'count')}`,
      `conflict409=${value(m, 'idem_conflict', 'count')}`,
      `other=${value(m, 'idem_other', 'count')}`,
      `mismatch=${value(m, 'idem_mismatch', 'count')}`,
      `payments_found=${value(m, 'idem_payments_found', 'count')}`,
      `duplicate_orders=${value(m, 'idem_duplicate_orders', 'count')}`,
    ].join(' ');
  } else {
    const fmt = (name) => {
      const v = m[name] ? m[name].values : null;
      return v ? `${name.replace('_duration', '')}_p50=${v.med.toFixed(1)} ${name.replace('_duration', '')}_p95=${v['p(95)'].toFixed(1)} ${name.replace('_duration', '')}_max=${v.max.toFixed(1)}` : `${name}=none`;
    };
    line = [
      `scenario=load vus=${VUS} key=${USE_KEY ? 'on' : 'off'} duration=${DURATION}`,
      `flows=${value(m, 'flows', 'count')}`,
      // Counter의 rate는 setup·워밍업까지 포함한 전체 시간 기준 → 측정 시간으로 직접 나눔
      `flows_per_s=${(value(m, 'flows', 'count') / seconds(DURATION)).toFixed(1)}`,
      `error_pct=${(value(m, 'flow_errors', 'rate') * 100).toFixed(2)}`,
      fmt('order_duration'), fmt('payment_duration'), fmt('flow_duration'),
    ].join(' ');
  }
  return { stdout: `RESULT ${line}\n` };
}
