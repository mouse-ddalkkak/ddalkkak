// 1 홈 서버 상태 · 연결 끊김 (F-CONN-07) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  codeLine,
  gaugeSubs,
  homeGauges,
  lines,
  runChip,
  scanButton,
  scanTarget,
  serverState,
  shownHealth,
  updateBanner,
  updateBlocksScan,
  updateFlow,
  updatePhase,
  UPDATE_STUCK_MS,
  waitingReapprove,
} from '../src/pages/home/homeState.ts'
import type { CodeSource } from '../src/api/codeSource.ts'
import type { Home } from '../src/api/home.ts'

const svc = (connected: boolean): Home['service'] => ({
  id: 'svc_1',
  name: 'order-api',
  host: 'localhost',
  port: 8080,
  connected,
  apiCount: 14,
  disconnect: { failures: 0, needsConfirm: false, lastCheckedAt: null },
  starterOutdated: false,
})
const health: NonNullable<Home['health']> = {
  cpu: 0.42,
  mem: { usedMb: 5017, totalMb: 8192 },
  disk: { usedGb: 38, totalGb: 80 },
  uptimeSec: 3600,
  serverTime: '2026-10-21T14:35:00+09:00',
}

test('끊기면(health null) 게이지 3개를 지우지 않고 값 없이 남긴다', () => {
  assert.deepEqual(
    homeGauges(null).map((g) => [g.k, g.v]),
    [
      ['CPU', null],
      ['메모리', null],
      ['디스크', null],
    ],
  )
})

test('다시 연결되면 게이지가 값을 되찾는다', () => {
  assert.deepEqual(
    homeGauges(health).map((g) => [g.k, g.v]),
    [
      ['CPU', 42],
      ['메모리', 61],
      ['디스크', 48],
    ],
  )
})

test('딸깍 검사: 서비스가 없으면 0-3 ①, 끊겼는데 다시 허용할 요청이 있으면 0-3 ② 서비스 연결(-222), 저장한 코드 연결이 없으면 0-3 ④, 있으면 바로 준비 (-224 · -245)', () => {
  assert.equal(scanTarget(null, false, true), '#/onboarding')
  assert.equal(scanTarget(svc(false), true, true), '#/onboarding/2')
  // 앱이 꺼지기만 한 것 — 다시 켜면 저장한 토큰으로 바로 연결돼 0-3 에는 카드가 안 뜬다
  assert.equal(scanTarget(svc(false), false, true), 'prepare')
  assert.equal(scanTarget(svc(true), true, true), 'prepare')
  // 검사를 시작할 때 코드 연결 화면(옛 #/code)을 거치지 않는다
  assert.equal(scanTarget(svc(true), false, false), '#/onboarding/4')
})

test('검사 전 칩 팝업 버튼: 코드 연결을 받는 동안은 "확인 중…"(꺼짐), 못 받으면 "딸깍 검사 시작"으로 살려 검사 시작에 맡긴다 (!145 리뷰)', () => {
  assert.deepEqual(scanButton(svc(true), undefined, false), { target: null, label: '확인 중…' })
  // 꺼진 "서비스 연결하러 가기"로 남지 않는다 — useScan이 홈 · 코드 연결을 새로 받아 갈 곳을 다시 정한다
  assert.deepEqual(scanButton(svc(true), undefined, true), { target: 'prepare', label: '딸깍 검사 시작' })
  assert.deepEqual(scanButton(svc(true), true, false), { target: 'prepare', label: '딸깍 검사 시작' })
  assert.deepEqual(scanButton(svc(true), false, false), { target: '#/onboarding/4', label: '소스코드 연결하러 가기' })
  // 서비스가 없으면 코드 연결을 기다리지 않는다
  assert.deepEqual(scanButton(null, undefined, false), { target: '#/onboarding', label: '서비스 연결하러 가기' })
})

const saved = (type: CodeSource['type'], ref: string | null, branch: string | null = null): CodeSource => ({
  type, ref, branch, lastCommit: null, lastCommitAt: null, lastFetchedAt: null, sameVersion: null, decompiled: false, fileHash: null,
})

test('홈 코드 줄: 저장한 방식마다 한 줄, 없으면 "코드 연결 안 함" (screens.md 1)', () => {
  assert.equal(codeLine(saved('github', 'https://github.com/owner/order-api', 'main')), 'GitHub owner/order-api · main')
  // GitLab · 자체 호스트 저장소에 "GitHub"를 붙이지 않는다 (!145 리뷰)
  assert.equal(codeLine(saved('github', 'https://gitlab.com/a/b', 'dev')), 'GitLab a/b · dev')
  assert.equal(codeLine(saved('github', 'https://gitlab.example.com/team/app')), 'Git gitlab.example.com/team/app')
  assert.equal(codeLine(saved('github', 'https://www.github.com/owner/order-api')), 'GitHub owner/order-api')
  assert.equal(codeLine(saved('jar', '/home/me/order-api/build/libs/order-api.jar')), '실행 파일 order-api.jar')
  assert.equal(codeLine(saved('zip', 'code.zip')), 'ZIP code.zip')
  assert.equal(codeLine(saved('local', '/home/me/order-api')), '내 컴퓨터 /home/me/order-api')
  assert.equal(codeLine(saved('none', null)), '코드 없이 검사')
  assert.equal(codeLine(null), '코드 연결 안 함')
})

test('다시 허용 대기: 끊긴 서비스와 이름 · host · port가 다 같은 요청만', () => {
  const req = { appName: 'order-api', host: 'localhost', port: 8080 }
  assert.equal(waitingReapprove(svc(false), [req]), true)
  // 같은 이름 · 다른 포트(예: 고객 앱의 RANDOM_PORT 시험) — 허용하면 409라 0-3으로 보내지 않는다
  assert.equal(waitingReapprove(svc(false), [{ ...req, port: 54321 }]), false)
  assert.equal(waitingReapprove(svc(false), [{ ...req, host: '10.0.0.5' }]), false)
  assert.equal(waitingReapprove(svc(true), [req]), false)
  assert.equal(waitingReapprove(null, [req]), false)
})

test('끊긴 서비스는 "연결 끊김 · 앱이 꺼져 있어요"', () => {
  assert.deepEqual(serverState(svc(false)), { ok: false, text: '연결 끊김 · 앱이 꺼져 있어요' })
})

test('연결돼 있으면 정상', () => {
  assert.deepEqual(serverState(svc(true)), { ok: true, text: '정상' })
})

test('앱을 연결하기 전에는 끊김이 아니라 "앱 연결 전"을 회색으로 보인다 (-256)', () => {
  assert.deepEqual(serverState(null), { ok: false, idle: true, text: '앱 연결 전 · 딸깍 서버 기준' })
})

test('앱을 연결하기 전에도 게이지가 값을 보인다 — 딸깍이 도는 곳을 잰 hostHealth (-256)', () => {
  const host = { ...health, cpu: 0.08 }
  assert.equal(shownHealth({ service: null, health: null, hostHealth: host }), host)
  assert.deepEqual(
    homeGauges(shownHealth({ service: null, health: null, hostHealth: host })).map((g) => g.v),
    [8, 61, 48],
  )
  assert.deepEqual(gaugeSubs(host, true), ['딸깍 서버', '4.9 / 8 GB · 딸깍 서버', '38 / 80 GB · 딸깍 작업 폴더 디스크'])
  assert.deepEqual(gaugeSubs(null, true), ['딸깍 서버', '딸깍 서버', '딸깍 작업 폴더 디스크'])
  // 연결된 뒤는 스타터킷 생존 신호만 — 끊기면 회색이지 딸깍 서버 값으로 바꾸지 않는다
  assert.equal(shownHealth({ service: svc(true), health, hostHealth: null }), health)
  assert.equal(shownHealth({ service: svc(false), health: null, hostHealth: host }), null)
})

// -214 홈 검사 상태 칩 · 측정 설명 · 위험 줄
const run = (state: NonNullable<Home['run']>['state'], runId: string | null): Home['run'] => ({ state, runId, interruptedAt: null })

test('검사 상태 칩: 검사 전 → 새 검사(링크 없이 "검사 시작" 팝업, -245), 검사 중 → 그 회차 진행, 검사 완료 → 그 결과', () => {
  assert.deepEqual(runChip(svc(true), run('none', null)), { label: '검사 전', href: null, state: 'none' })
  assert.deepEqual(runChip(svc(true), run('running', 'run_6')), { label: '검사 중', href: '#/runs/run_6/progress', state: 'running' })
  assert.deepEqual(runChip(svc(true), run('done', 'run_5')), { label: '검사 완료', href: '#/runs/run_5', state: 'done' })
})

test('검사 상태 칩: 연결 안 됨도 검사 전(팝업이 갈 곳을 알려 준다, -245)', () => {
  assert.deepEqual(runChip(null, null), { label: '검사 전', href: null, state: 'none' })
})

test('검사 상태 칩: 딸깍 재시작으로 멈춘 회차는 검사 멈춤 → 그 회차 5에서 이어서 검사할지 묻는다 (-228)', () => {
  assert.deepEqual(runChip(svc(true), run('interrupted', 'run_6')), {
    label: '검사 멈춤',
    href: '#/runs/run_6/progress',
    state: 'interrupted',
  })
})

test('검사 상태 칩: 끝난 회차의 결과(6 · 7)를 보는 동안은 검사 완료, 다른 페이지로 가면 검사 전', () => {
  const seen = run('none', null)
  assert.deepEqual(runChip(svc(true), seen, 'run_5', '#/runs/run_5'), { label: '검사 완료', href: '#/runs/run_5', state: 'done' })
  assert.equal(runChip(svc(true), seen, 'run_5', '#/runs/run_5/issues/p1').label, '검사 완료')
  assert.equal(runChip(svc(true), seen, 'run_5', '#/history').label, '검사 전')
  // 이력에서 연 옛 회차 · 다른 회차는 검사 완료가 아니다
  assert.equal(runChip(svc(true), seen, 'run_5', '#/runs/run_4').label, '검사 전')
})

test('게이지 아래 줄: 무엇을 잰 값인지 · 단위. CPU도 같은 줄이 있다', () => {
  assert.deepEqual(gaugeSubs(health), ['시스템 전체', '4.9 / 8 GB · 시스템 전체', '38 / 80 GB · 앱 작업 폴더 디스크'])
  // 끊겨도 무엇을 재는지는 남긴다
  assert.deepEqual(gaugeSubs(null), ['시스템 전체', '시스템 전체', '앱 작업 폴더 디스크'])
})

test('위험 · 주의 줄은 영역마다 한 줄 (서버가 줄바꿈으로 이어 준다)', () => {
  assert.deepEqual(lines('성능 2 · N+1 의심\n보안 1 · SQL 문자열 연결\n코드 품질 1 · SQL 문자열 결합'), [
    '성능 2 · N+1 의심',
    '보안 1 · SQL 문자열 연결',
    '코드 품질 1 · SQL 문자열 결합',
  ])
  assert.deepEqual(lines(null), [])
})

// 새 버전 띠 (F-HOME-06, -221)
test('새 버전이 없거나 아직 못 받았으면 띠가 없다', () => {
  assert.equal(updateBanner(undefined, false), null)
  assert.equal(updateBanner({ current: '1.2.2', latest: null, canUpdate: true }, false), null)
  assert.equal(updateBanner({ current: null, latest: null, canUpdate: false }, false), null)
})

test('설치기로 깐 딸깍은 업데이트 버튼, 검사 중이면 막고 이유를 보인다', () => {
  assert.deepEqual(updateBanner({ current: '1.2.1', latest: '1.2.2', canUpdate: true }, false), {
    text: '새 버전 v1.2.2가 나왔어요 · 지금 v1.2.1',
    button: true,
    disabled: false,
    hint: null,
    command: null,
  })
  assert.deepEqual(updateBanner({ current: '1.2.1', latest: '1.2.2', canUpdate: true }, true), {
    text: '새 버전 v1.2.2가 나왔어요 · 지금 v1.2.1',
    button: true,
    disabled: true,
    hint: '검사가 끝나면 업데이트할 수 있어요',
    command: null,
  })
})

test('Compose로 띄운 출시 이미지는 버튼 대신 태그를 바꾸고 pull · up 하라는 명령', () => {
  assert.deepEqual(updateBanner({ current: '1.2.1', latest: '1.2.2', canUpdate: false }, false), {
    text: '새 버전 v1.2.2가 나왔어요 · 지금 v1.2.1',
    button: false,
    disabled: false,
    hint: 'docker-compose.yml의 이미지 태그를 1.2.2로 바꾸고',
    command: 'docker compose pull && docker compose up -d',
  })
})

test('업데이트를 누른 뒤 서버가 바뀌기 전까지는 어느 화면에서든 검사를 막고, 5분이 지나면 멈춘 것으로 보고 풀어 준다 (!165 리뷰 2-1)', () => {
  updateFlow.fail()
  assert.equal(updatePhase(), null)
  assert.equal(updateBlocksScan(), false)
  updateFlow.start() // POST 중 — 새 이미지를 받는 동안(최대 5분)
  assert.equal(updatePhase(), 'starting')
  assert.equal(updateBlocksScan(), true)
  updateFlow.started(1_000) // 202 — 설치기가 서버를 바꾼다
  assert.equal(updatePhase(1_000 + UPDATE_STUCK_MS - 1), 'started')
  assert.equal(updateBlocksScan(1_000 + UPDATE_STUCK_MS - 1), true)
  assert.equal(updatePhase(1_000 + UPDATE_STUCK_MS), 'stuck')
  assert.equal(updateBlocksScan(1_000 + UPDATE_STUCK_MS), false)
  updateFlow.fail() // 409 · 502
  assert.equal(updatePhase(), null)
})
