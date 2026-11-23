// 화면 4 로그인 줄 · 팝업 (F-SCOPE-09, -230) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { authBody, authLine, choosable, defaultChoice } from '../src/utils/authLine.ts'
import type { AuthSetting } from '../src/api/runs.ts'

const analysis = { loginApi: 'POST /api/auth/login', signupApi: 'POST /api/auth/signup', fields: ['email', 'password'], fieldsGuessed: false }
const none: AuthSetting = { state: 'none', analysis, mode: null, hint: null, tokenAt: null, lastUsedAt: null }

test('상태 줄 — 설정 전 · 설정됨 · 만료 · 키 분실 (screens.md 4)', () => {
  assert.deepEqual(authLine(none), { text: '설정 전 · 로그인이 필요한 API는 건너뛰어요', warn: true, action: '설정' })
  assert.deepEqual(authLine({ ...none, state: 'ok', mode: 'account', hint: 'dd…7f2a', tokenAt: 'body.token' }), {
    text: '계정 dd…7f2a · 토큰 위치 body.token',
    warn: false,
    action: '바꾸기',
  })
  assert.equal(authLine({ ...none, state: 'ok', mode: 'cookie', hint: 'SESSION=****' }).text, '쿠키 SESSION=****')
  assert.deepEqual(authLine({ ...none, state: 'expired', mode: 'token', hint: '…a1b2' }), {
    text: '등록한 값이 거절됐어요 · 다시 넣어 주세요',
    warn: true,
    action: '다시 등록',
  })
  // !141 리뷰 6-3: 방식 이름을 넣으면 쿠키 조사가 틀린다("쿠키이") — screens.md 문구 그대로
  assert.equal(authLine({ ...none, state: 'expired', mode: 'cookie', hint: 'SESSION=****' }).text, '등록한 값이 거절됐어요 · 다시 넣어 주세요')
  assert.equal(authLine({ ...none, state: 'key_lost', mode: 'cookie' }).action, '다시 등록')
})

test('팝업 첫 방식 — 로그인 API가 없으면 토큰, 가입 API가 있으면 딸깍이 만들기, 지난 방식이 있으면 그것', () => {
  assert.equal(defaultChoice(none), 'create')
  assert.equal(defaultChoice({ ...none, analysis: { ...analysis, signupApi: null } }), 'account')
  assert.equal(defaultChoice({ ...none, analysis: { ...analysis, loginApi: null } }), 'token')
  assert.equal(defaultChoice({ ...none, state: 'expired', mode: 'cookie' }), 'cookie')
  assert.equal(defaultChoice({ ...none, state: 'ok', mode: 'account', hint: 'me….com' }), 'account')
})

test('지난 방식이 계정이면 아이디가 dd로 시작해도 내 계정 — 새 계정이 가입돼 내 계정을 덮지 않게 (!141 리뷰 4-2)', () => {
  assert.equal(defaultChoice({ ...none, state: 'ok', mode: 'account', hint: 'dd….com' }), 'account')
  assert.equal(defaultChoice({ ...none, state: 'expired', mode: 'account', hint: 'dd…7f2a' }), 'account')
  assert.equal(authBody('account', { username: '', password: '', token: '', cookie: '' }), null)
})

test('로그인 API가 없으면 계정 방식은 고를 수 없다 (DET-04)', () => {
  const noLogin = { ...none, analysis: { ...analysis, loginApi: null, signupApi: null } }
  assert.equal(choosable(noLogin, 'create'), false)
  assert.equal(choosable(noLogin, 'account'), false)
  assert.equal(choosable(noLogin, 'token'), true)
  assert.equal(choosable({ ...none, analysis: { ...analysis, signupApi: null } }, 'create'), false)
})

test('PUT 본문 — 빈 칸이면 null', () => {
  const empty = { username: '', password: '', token: '', cookie: '' }
  assert.deepEqual(authBody('create', empty), { mode: 'account', create: true })
  assert.equal(authBody('account', { ...empty, username: 'me@shop.com' }), null)
  assert.deepEqual(authBody('account', { ...empty, username: ' me@shop.com ', password: 'pw 1' }), {
    mode: 'account',
    username: 'me@shop.com',
    password: 'pw 1',
  })
  assert.equal(authBody('token', { ...empty, token: '  ' }), null)
  assert.deepEqual(authBody('cookie', { ...empty, cookie: 'SESSION=abc' }), { mode: 'cookie', cookie: 'SESSION=abc' })
})
