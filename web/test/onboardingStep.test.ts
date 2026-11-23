// 0-3 온보딩 단계 (-222) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ONBOARDING_PATH,
  canNext,
  connectedNormally,
  detectedText,
  homeLabel,
  kitEnv,
  needsConfirm,
  nextHash,
  parseStep,
  prevHash,
  redirectHome,
  showRequests,
  stepChips,
} from '../src/pages/onboarding/onboardingStep.ts'
import type { Home } from '../src/api/home.ts'

const svc = (connected: boolean, failures = 0): Home['service'] => ({
  id: 'svc_1',
  name: 'order-api',
  host: 'localhost',
  port: 8080,
  connected,
  apiCount: 14,
  disconnect: { failures, needsConfirm: failures >= 3, lastCheckedAt: null },
})

test('주소의 단계 번호를 그대로 쓴다 — 새로고침해도 같은 단계', () => {
  assert.equal(parseStep('1'), 1)
  assert.equal(parseStep('3'), 3)
  assert.equal(parseStep('4'), 4)
})

test('주소 → 단계: #/onboarding/3을 새로고침해도 ③', () => {
  const step = (path: string) => {
    const m = ONBOARDING_PATH.exec(path)
    return m ? parseStep(m[1]) : null
  }
  assert.equal(step('/onboarding/3'), 3)
  assert.equal(step('/onboarding'), 1)
  assert.equal(step('/onboarding/12'), null) // 0-3이 아니다 → 라우트 표가 홈으로
  assert.equal(step('/onboarding/2/x'), null)
})

test('번호가 없거나 범위 밖이면 ①', () => {
  assert.equal(parseStep(undefined), 1)
  assert.equal(parseStep('0'), 1)
  assert.equal(parseStep('9'), 1)
})

test('다음은 ① → ② → ③ → ④ → 홈', () => {
  assert.equal(nextHash(1), '#/onboarding/2')
  assert.equal(nextHash(2), '#/onboarding/3')
  assert.equal(nextHash(3), '#/onboarding/4')
  assert.equal(nextHash(4), '#/')
})

test('이전은 한 단계 앞으로', () => {
  assert.equal(prevHash(2), '#/onboarding/1')
  assert.equal(prevHash(4), '#/onboarding/3')
})

test('② 서비스 연결은 연결 전엔 다음으로 못 간다', () => {
  assert.equal(canNext(2, false), false)
  assert.equal(canNext(2, true), true)
})

test('① · ③(AI 건너뛰기) · ④는 연결 없이도 다음', () => {
  assert.equal(canNext(1, false), true)
  assert.equal(canNext(3, false), true)
  assert.equal(canNext(4, false), true)
})

test('칩은 앞 단계 완료 · 지금 진행 중 · 뒤 대기', () => {
  assert.deepEqual(
    stepChips(3).map((c) => c.state),
    ['done', 'done', 'on', 'todo'],
  )
})

test('③ AI를 건너뛰고 지나오면 완료가 아니라 건너뜀 (-250)', () => {
  assert.deepEqual(
    stepChips(4, true).map((c) => c.state),
    ['done', 'done', 'skipped', 'on'],
  )
  assert.deepEqual(
    stepChips(4, false).map((c) => c.state),
    ['done', 'done', 'done', 'on'],
  )
  assert.equal(stepChips(3, true)[2].state, 'on') // ③에 있는 동안은 진행 중
})

test('④에서 고르거나 넣은 값이 연결 전이면 "연결하지 않고 홈으로" (-250)', () => {
  assert.equal(homeLabel(true), '연결하지 않고 홈으로')
  assert.equal(homeLabel(false), '홈으로')
})

test('① 감지됨에 Unknown을 설명 없이 보이지 않는다 (-250)', () => {
  assert.equal(detectedText('21', 'Unknown'), 'Java 21')
  assert.equal(detectedText('21', 'Gradle'), 'Java 21 · Gradle')
  assert.equal(detectedText(null, 'Maven'), 'Maven')
  assert.equal(detectedText(null, 'Unknown'), null)
  assert.equal(detectedText(undefined, undefined), null)
})

test('정상 연결 = 연결됨이고 해제 확인이 필요 없음 (F-CONN-08)', () => {
  assert.equal(connectedNormally(svc(true)), true)
  assert.equal(connectedNormally(svc(true, 2)), true) // 1 ~ 2번 실패는 알리지 않는다
  assert.equal(connectedNormally(svc(true, 3)), false)
  assert.equal(connectedNormally(svc(false)), false)
  assert.equal(connectedNormally(null), false)
  assert.equal(connectedNormally({ ...svc(true)!, disconnect: null }), true) // 점검 전 Spring
})

test('3번 연속 실패하면 해제 확인이 필요하다', () => {
  assert.equal(needsConfirm(svc(false, 3)), true)
  assert.equal(needsConfirm(svc(false, 2)), false)
  assert.equal(needsConfirm(null), false)
})

test('② 해제 안내가 떠도 대기 목록 · 다시 찾기는 아래에 보인다 — 같은 앱은 다시 허용, 다른 앱은 409 모달 (-250)', () => {
  const silent = { ...svc(false)!, disconnect: { failures: 0, needsConfirm: true, lastCheckedAt: null } } // 생존 신호 1분 30초 끊김
  assert.equal(showRequests(silent, false), true)
  assert.equal(showRequests(svc(false, 3), false), true) // 점검 3번 실패
  assert.equal(showRequests(svc(true, 3), false), true)
  assert.equal(showRequests(svc(false), false), true) // 끊김 — 토큰 파일을 잃은 앱 (-211)
  assert.equal(showRequests(null, false), true) // 연결 전 · 해제한 뒤
  assert.equal(showRequests(svc(true), false), false) // 정상 연결 → "✓ 연결됨"만
  assert.equal(showRequests(silent, true), false) // 이 화면에서 방금 허용
})

test('정상 연결 중 ① · ② 주소로 들어오면 홈, ③ · ④는 그 단계 (-223)', () => {
  assert.equal(redirectHome(1, svc(true)), true)
  assert.equal(redirectHome(2, svc(true)), true)
  assert.equal(redirectHome(3, svc(true)), false)
  assert.equal(redirectHome(4, svc(true)), false)
  assert.equal(redirectHome(2, svc(false)), false) // 끊겼으면 0-3에 머문다
  assert.equal(redirectHome(2, svc(true, 3)), false) // 해제 확인 필요
  assert.equal(redirectHome(2, null), false) // 해제한 뒤
})

test('① 딸깍이 7777이 아닌 포트면 앱에 넣을 DDALKKAK_URL 줄, 7777이거나 아직 모르면 없음 (-151)', () => {
  assert.equal(kitEnv('http://localhost:7778'), 'DDALKKAK_URL=http://localhost:7778')
  assert.equal(kitEnv('http://localhost:7777'), null)
  assert.equal(kitEnv(undefined), null)
})
