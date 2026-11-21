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

/** 칩은 위치 기준: 앞 완료 · 지금 진행 중 · 뒤 대기. ③ AI는 키 없이 지나왔으면 완료가 아니라 건너뜀 (-250) */
export const stepChips = (step: OnbStep, aiSkipped = false): Step[] =>
  STEPS.map((label, i) => ({
    label,
    state: i + 1 < step ? (i === 2 && aiSkipped ? 'skipped' : 'done') : i + 1 === step ? 'on' : 'todo',
  }))

/** ④ 오른쪽 버튼 — 고르거나 넣은 값이 연결 전이면 버려진다는 걸 이름으로 알린다 (-250) */
export const homeLabel = (dirty: boolean) => (dirty ? '연결하지 않고 홈으로' : '홈으로')

/** ① "감지됨" — 스타터킷이 빌드 파일을 못 찾으면 Unknown을 보낸다. 설명 없이 보이지 않게 뺀다 (-250). 보일 게 없으면 null */
export function detectedText(javaVersion: string | null | undefined, buildTool: string | null | undefined): string | null {
  const parts = [javaVersion && `Java ${javaVersion}`, buildTool && buildTool !== 'Unknown' && buildTool].filter(Boolean)
  return parts.length ? parts.join(' · ') : null
}

/** ① 스타터킷은 http://localhost:7777 을 찾는다. 딸깍이 다른 포트로 열렸으면 앱에 넣을 환경 변수 줄 (-151) */
export const kitEnv = (kitUrl: string | undefined) =>
  !kitUrl || kitUrl === 'http://localhost:7777' ? null : `DDALKKAK_URL=${kitUrl}`

/** 연결 점검이 3번 연속 실패했거나 생존 신호가 1분 30초 끊겨 해제 안내를 띄울 때 (F-CONN-09, -223 · -250) */
export const needsConfirm = (service: Home['service']) => !!service?.disconnect?.needsConfirm

/** 정상 연결 = 연결됨이고 해제 확인이 필요 없음 (F-CONN-08) */
export const connectedNormally = (service: Home['service']) => !!service?.connected && !needsConfirm(service)

/** ② 대기 요청 목록 · "다시 찾기"는 이 화면에서 허용했거나 정상 연결이 아니면 늘 보인다 — 해제 안내가 떠도 그 아래에.
 *  같은 앱을 다시 허용하면 새 토큰(F-CONN-04, 토큰 파일을 잃은 앱), 다른 앱이면 409 모달 "기존 연결 해제" (-250) */
export const showRequests = (service: Home['service'], approvedHere: boolean) => !approvedHere && !connectedNormally(service)

/** ① · ② 주소로 들어올 때 정상 연결이면 홈으로. ③ · ④는 늘 그 단계 — ②에서 방금 연결하고 넘어가는 것도 그대로 (-223) */
export const redirectHome = (step: OnbStep, service: Home['service']) => step <= 2 && connectedNormally(service)
