import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // 백엔드 CORS 기본 허용 출처가 http://localhost:5173 → 다른 포트로 밀려나면 CORS 403이 나므로 고정
    port: 5173,
    strictPort: true,
  },
  build: {
    rollupOptions: {
      output: {
        // 라이브러리는 화면 코드보다 덜 바뀜 → 별도 청크로 두면 배포 후에도 브라우저 캐시가 유지됨
        manualChunks(id) {
          // 웹폰트 CSS는 첫 렌더 뒤 동적으로 받으므로 vendor(즉시 로드)에 섞지 않음
          if (id.includes('@fontsource')) return undefined;
          if (id.includes('node_modules')) return 'vendor';
        },
      },
    },
  },
  test: {
    environment: 'node',
  },
});
