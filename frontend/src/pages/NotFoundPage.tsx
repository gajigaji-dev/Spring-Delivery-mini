import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <section>
      <h1>페이지를 찾을 수 없습니다</h1>
      <Link to="/">매장 목록으로</Link>
    </section>
  );
}
