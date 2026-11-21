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

        <Route path="cart" element={<RequireRole roles={['CUSTOMER']}><P.cart /></RequireRole>} />
        <Route path="orders" element={<RequireRole roles={['CUSTOMER', 'OWNER']}><P.orders /></RequireRole>} />
        <Route path="orders/:id" element={<RequireRole roles={['CUSTOMER', 'OWNER']}><P.orderDetail /></RequireRole>} />
        <Route path="payments" element={<RequireRole roles={['CUSTOMER']}><P.payments /></RequireRole>} />
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
