/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 가짜 모드 (web/.env.fake, `npm run dev:fake`). 없으면 실제 /api/* — docs/web.md §6 */
  readonly VITE_FAKE?: string
}
