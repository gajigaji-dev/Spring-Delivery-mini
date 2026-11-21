import { IconBell, IconInfo, IconStore } from '../../components/Icons';
import { PrefetchLink } from '../../components/PrefetchLink';
import type { StreamStatus } from '../../owner/orderStreamController';
import { useOwner } from '../../owner/OwnerContext';

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

const STREAM_LABEL: Record<StreamStatus, { text: string; tone: string; title: string }> = {
  live: { text: '실시간 연결됨', tone: 'badge--ok', title: '새 주문이 들어오면 바로 표시됩니다.' },
  connecting: { text: '연결 중…', tone: 'badge--prog', title: '실시간 알림 서버에 연결하고 있습니다.' },
  polling: {
    text: '30초마다 확인 중',
    tone: 'badge--warn',
    title: '실시간 연결이 끊겨 30초마다 주문을 다시 불러옵니다. 연결되면 자동으로 돌아갑니다.',
  },
  stopped: { text: '실시간 알림 꺼짐', tone: '', title: '권한 문제로 연결하지 못했습니다. 다시 로그인해 주세요.' },
};

/** 실시간 주문 알림 상태 + '알림 소리 켜기' (#76) — 소리는 브라우저 정책상 클릭으로만 켤 수 있음 */
export function LiveStatus() {
  const { streamStatus, soundOn, toggleSound, soundAvailable } = useOwner();
  if (!streamStatus) return null;
  const label = STREAM_LABEL[streamStatus];
  // 부모의 .ph__actions 안에 다른 버튼과 나란히 놓임
  return (
    <>
      <span className={`badge ${label.tone}`} title={label.title} role="status" aria-live="polite">
        {label.text}
      </span>
      {soundAvailable && (
        <button
          type="button"
          className={`btn btn--sm ${soundOn ? 'btn--soft' : 'btn--ghost'}`}
          onClick={() => void toggleSound()}
          aria-pressed={soundOn}
        >
          <IconBell size={16} />
          {soundOn ? '알림 소리 끄기' : '알림 소리 켜기'}
        </button>
      )}
    </>
  );
}
