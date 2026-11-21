import { IconInfo, IconStore } from '../../components/Icons';
import { PrefetchLink } from '../../components/PrefetchLink';

/** 매장이 없는 사장님(승인 직후 등) — 매장부터 만들도록 안내 */
export function NoStore() {
  return (
    <div className="center-panel">
      <div className="lpanel">
        <div className="empty__icon empty__icon--hold">
          <IconStore />
        </div>
        <h1>아직 등록한 매장이 없어요</h1>
        <p>매장을 추가하고 메뉴를 등록하면 주문과 정산을 이 화면에서 관리할 수 있어요.</p>
        <PrefetchLink to="/owner/stores" page="ownerStores" className="btn">
          매장 추가하기
        </PrefetchLink>
      </div>
    </div>
  );
}

/** 주문이 너무 많아 끝까지 받지 못했을 때 — 최신 주문이 빠질 수 있음을 숨기지 않음 */
export function TruncatedNotice() {
  return (
    <div className="error" role="status">
      <IconInfo />
      <span>주문이 많아 일부만 불러왔어요. 최근 주문과 오늘 집계가 빠졌을 수 있습니다.</span>
    </div>
  );
}
