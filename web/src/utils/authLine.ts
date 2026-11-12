import type { AuthInput, AuthSetting } from '../api/runs'

// 4 로그인 줄 · 팝업 (screens.md 4 "로그인 줄", F-SCOPE-09, -230)

/** 팝업 방식 — 계정은 "딸깍이 만들기"와 "내 계정" 둘로 나눠 보인다 */
export type AuthChoice = 'create' | 'account' | 'token' | 'cookie'

const MODE_NAME: Record<string, string> = { account: '계정', token: '토큰', cookie: '쿠키' }

/** 상태 한 줄 · 주황으로 보일지 · 버튼 이름 */
export function authLine(a: AuthSetting): { text: string; warn: boolean; action: string } {
  switch (a.state) {
    case 'ok':
      return {
        text: `${MODE_NAME[a.mode ?? ''] ?? a.mode} ${a.hint ?? ''}${a.tokenAt ? ` · 토큰 위치 ${a.tokenAt}` : ''}`,
        warn: false,
        action: '바꾸기',
      }
    case 'expired':
      return { text: '등록한 값이 거절됐어요 · 다시 넣어 주세요', warn: true, action: '다시 등록' }
    case 'key_lost':
      return { text: '암호화 키가 바뀌어 저장한 값을 지웠어요 · 다시 등록해 주세요', warn: true, action: '다시 등록' }
    default:
      return { text: '설정 전 · 로그인이 필요한 API는 건너뛰어요', warn: true, action: '설정' }
  }
}

/**
 * 팝업을 열 때 고를 방식: 지난 방식 → 로그인 API가 없으면 토큰 → 가입 API가 있으면 딸깍이 만들기 → 내 계정.
 * 지난 방식이 계정이면 늘 '내 계정' — 가린 값으로는 딸깍이 만든 계정인지 가를 수 없고(사용자 아이디도 dd…로 시작할 수 있다),
 * 그대로 확인하면 새 계정이 가입돼 저장한 내 계정을 덮는다. '내 계정'은 칸이 비어 있어 확인 버튼이 막힌다 (!141 리뷰 4-2)
 */
export function defaultChoice(a: AuthSetting): AuthChoice {
  if (a.mode === 'token' || a.mode === 'cookie') return a.mode
  if (!a.analysis.loginApi) return 'token'
  if (a.mode === 'account') return 'account'
  return a.analysis.signupApi ? 'create' : 'account'
}

/** 고를 수 있는지 — 계정 둘은 로그인 API가, 딸깍이 만들기는 가입 API도 있어야 한다 */
export function choosable(a: AuthSetting, c: AuthChoice): boolean {
  if (c === 'create') return !!a.analysis.loginApi && !!a.analysis.signupApi
  if (c === 'account') return !!a.analysis.loginApi
  return true
}

/** 팝업 값 → PUT 본문. 빈 칸이 있으면 null (버튼을 막는다) */
export function authBody(c: AuthChoice, v: { username: string; password: string; token: string; cookie: string }): AuthInput | null {
  switch (c) {
    case 'create':
      return { mode: 'account', create: true }
    case 'account':
      return v.username.trim() && v.password ? { mode: 'account', username: v.username.trim(), password: v.password } : null
    case 'token':
      return v.token.trim() ? { mode: 'token', token: v.token.trim() } : null
    default:
      return v.cookie.trim() ? { mode: 'cookie', cookie: v.cookie.trim() } : null
  }
}
