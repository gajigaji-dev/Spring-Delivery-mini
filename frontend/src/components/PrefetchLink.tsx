import { NavLink, type NavLinkProps } from 'react-router-dom';
import { preloadPage, type PageName } from '../routes/pages';

/** 마우스를 올리거나 포커스·터치하는 순간 다음 화면 청크를 미리 받음 (클릭까지 보통 100ms 이상 여유) */
export function PrefetchLink({ page, ...props }: NavLinkProps & { page: PageName }) {
  const preload = () => preloadPage(page);
  return <NavLink {...props} onMouseEnter={preload} onFocus={preload} onTouchStart={preload} />;
}
