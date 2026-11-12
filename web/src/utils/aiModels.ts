// AI 연결 폼 모델 셀렉트 (F-AI-01, -213) — web/test/aiModels.test.ts
// 기본값은 ai/common/llm.py DEFAULT_MODELS와 같게 둔다(빈 값을 보내면 AI 서버가 그 모델을 쓴다). 새 모델이 나오면 list만 고친다
import type { AiProvider } from '../api/ai'

export const MODELS = {
  claude: { default: 'claude-haiku-5-5', list: ['claude-sonnet-5-5', 'claude-opus-5-5'] },
  openai: { default: 'gpt-4o-mini', list: ['gpt-6-luna', 'gpt-6.1-sol'] },
}
/** "직접 입력…" 칸의 값 — 고르면 입력칸이 나온다 */
export const CUSTOM = '__custom'

/** 셀렉트 칸. 직접 URL(custom)은 모델을 알 수 없어 null(입력칸) */
export function modelOptions(provider: AiProvider): { value: string; label: string }[] | null {
  if (provider === 'custom') return null
  const m = MODELS[provider]
  return [{ value: '', label: `기본 모델 (${m.default})` }, ...m.list.map((v) => ({ value: v, label: v })), { value: CUSTOM, label: '직접 입력…' }]
}

/** 연결 확인 · 저장에 보낼 model */
export function modelValue(choice: string, typed: string) {
  return choice === CUSTOM ? typed.trim() : choice
}
