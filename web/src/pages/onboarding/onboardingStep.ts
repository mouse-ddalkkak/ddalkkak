// 0-3 온보딩 단계 (-222) — web/test/onboardingStep.test.ts
// 단계는 주소 #/onboarding/N 에 둔다 → 새로고침해도 같은 단계
import type { Step } from '../../components/StepChips'
import type { Home } from '../../api/home'

export type OnbStep = 1 | 2 | 3 | 4

export const STEPS = ['① 스타터킷 추가', '② 서비스 연결', '③ AI 키 추가', '④ 소스코드 연결'] as const

/** 0-3 주소 #/onboarding · #/onboarding/N. N은 한 자리만 (App.tsx 라우트 표가 쓴다) */
export const ONBOARDING_PATH = /^\/onboarding(?:\/(\d))?$/

/** 주소의 번호 → 단계. 없거나 범위 밖이면 ① */
export function parseStep(s: string | undefined): OnbStep {
  const n = Number(s)
  return n >= 1 && n <= 4 ? (n as OnbStep) : 1
}

/** ② 서비스 연결은 연결돼야 다음. ③ AI는 건너뛸 수 있다 */
export const canNext = (step: OnbStep, connected: boolean) => step !== 2 || connected

export const nextHash = (step: OnbStep) => (step < 4 ? `#/onboarding/${step + 1}` : '#/')
export const prevHash = (step: OnbStep) => `#/onboarding/${Math.max(1, step - 1)}`

/** 칩은 위치 기준: 앞 완료 · 지금 진행 중 · 뒤 대기 */
export const stepChips = (step: OnbStep): Step[] =>
  STEPS.map((label, i) => ({ label, state: i + 1 < step ? 'done' : i + 1 === step ? 'on' : 'todo' }))

/** 연결 점검이 3번 연속 실패해 해제 안내를 띄울 때 (F-CONN-09, -223) */
export const needsConfirm = (service: Home['service']) => !!service?.disconnect?.needsConfirm

/** 정상 연결 = 연결됨이고 해제 확인이 필요 없음 (F-CONN-08) */
export const connectedNormally = (service: Home['service']) => !!service?.connected && !needsConfirm(service)

/** ① · ② 주소로 들어올 때 정상 연결이면 홈으로. ③ · ④는 늘 그 단계 — ②에서 방금 연결하고 넘어가는 것도 그대로 (-223) */
export const redirectHome = (step: OnbStep, service: Home['service']) => step <= 2 && connectedNormally(service)
