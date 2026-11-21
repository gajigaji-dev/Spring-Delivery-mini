import { Route, Routes } from 'react-router-dom';
import { RequireRole } from './auth/RequireRole';
import { Layout } from './components/Layout';
import { pages } from './routes/pages';

const P = pages;

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        {/* 랜딩: 비로그인 방문자용 사장님 소개, 로그인 사용자는 역할별 첫 화면으로 이동 */}
        <Route index element={<P.landing />} />
        <Route path="stores" element={<P.stores />} />
        <Route path="stores/:id" element={<P.storeDetail />} />
        <Route path="login" element={<P.login />} />
        <Route path="signup" element={<P.signup />} />
        {/* 소셜 로그인·연결 결과 (V22) — 서버 OAuth2 콜백이 ?result= 또는 ?error=를 붙여 보냄 */}
        <Route path="oauth/callback" element={<P.oauthCallback />} />

        <Route path="cart" element={<RequireRole roles={['CUSTOMER']}><P.cart /></RequireRole>} />
        <Route path="orders" element={<RequireRole roles={['CUSTOMER', 'OWNER']}><P.orders /></RequireRole>} />
        <Route path="orders/:id" element={<RequireRole roles={['CUSTOMER', 'OWNER']}><P.orderDetail /></RequireRole>} />
        <Route path="payments" element={<RequireRole roles={['CUSTOMER']}><P.payments /></RequireRole>} />
        {/* 토스 결제창이 돌아오는 주소 (V20) — 전체 페이지 이동이라 앱 시작 시 refresh로 로그인을 되살린 뒤 표시 */}
        <Route path="payments/toss/success" element={<RequireRole roles={['CUSTOMER']}><P.tossSuccess /></RequireRole>} />
        <Route path="payments/toss/fail" element={<RequireRole roles={['CUSTOMER']}><P.tossFail /></RequireRole>} />
        {/* 가짜 결제창 — 서버가 가짜 PG 모드일 때만 쓰임 (V21) */}
        <Route path="payments/fake-toss" element={<RequireRole roles={['CUSTOMER']}><P.fakeTossCheckout /></RequireRole>} />
        <Route path="notifications" element={<RequireRole><P.notifications /></RequireRole>} />
        <Route path="me" element={<RequireRole><P.me /></RequireRole>} />

        <Route path="owner" element={<RequireRole roles={['OWNER']}><P.ownerHome /></RequireRole>} />
        <Route path="owner/orders" element={<RequireRole roles={['OWNER']}><P.ownerOrders /></RequireRole>} />
        <Route path="owner/stores" element={<RequireRole roles={['OWNER']}><P.ownerStores /></RequireRole>} />
        <Route path="owner/settlements" element={<RequireRole roles={['OWNER']}><P.ownerSettlements /></RequireRole>} />

        <Route path="admin/settlements" element={<RequireRole roles={['ADMIN']}><P.adminSettlements /></RequireRole>} />
        <Route path="admin/outbox" element={<RequireRole roles={['ADMIN']}><P.adminOutbox /></RequireRole>} />
        <Route path="admin/owner-applications" element={<RequireRole roles={['ADMIN']}><P.adminOwnerApplications /></RequireRole>} />

        <Route path="*" element={<P.notFound />} />
      </Route>
    </Routes>
  );
}
