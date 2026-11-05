import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 중에는 vite(:5173)가 /api 를 Spring(:7777)으로 넘긴다. 배포 때는 Spring이 빌드 결과를 직접 서빙한다.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:7777' } },
})
