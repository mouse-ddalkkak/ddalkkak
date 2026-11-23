// 1 홈 도구 상태 팝업 (F-HOME-01 · openapi getTools, -229 · -245) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { toolRows } from '../src/pages/home/toolStatus.ts'
import type { Tool } from '../src/api/home.ts'

// 예시는 openapi에서 뽑은 contracts 그대로 (python scripts/screen_contracts.py)
const home = JSON.parse(readFileSync(new URL('../../contracts/4-screen/1-home.json', import.meta.url), 'utf8'))
const example = (name: string): Tool[] => home.calls['GET /api/tools'].responses[`200 ${name}`].tools

test('셋 다 실행 가능 → k6 · ZAP · Semgrep 세 줄 모두 정상 · 설치됨 · 실행 가능', () => {
  const rows = toolRows(example('ok'))
  assert.deepEqual(rows.map((r) => r.name), ['k6', 'ZAP', 'Semgrep'])
  for (const r of rows) {
    assert.equal(r.badge, '정상')
    assert.equal(r.ok, true)
    assert.equal(r.installed, '설치됨')
    assert.equal(r.runnable, '실행 가능')
    assert.equal(r.error, null)
  }
  assert.equal(rows[0].image, 'grafana/k6:1.8.1')
})

test('첫 검사 전(이미지 없음) → 미설치 · 정상 아님 · 조치 문구', () => {
  const rows = toolRows(example('notInstalled'))
  for (const r of rows) {
    assert.equal(r.badge, '미설치')
    assert.equal(r.ok, false)
    assert.equal(r.installed, '미설치')
    assert.notEqual(r.runnable, '실행 가능')
    assert.match(r.fix ?? '', /docker pull/)
  }
})

test('이미지 받기 실패 → 그 도구 줄에 단계(받기) · 원인 · 조치 · 검사 회차 링크', () => {
  const [k6, zap, semgrep] = toolRows(example('pullFailed'))
  assert.equal(k6.ok, true)
  assert.equal(semgrep.ok, true)
  assert.equal(zap.ok, false)
  assert.equal(zap.badge, '미설치')
  assert.deepEqual(zap.error && { stage: zap.error.stage, reason: zap.error.reason, href: zap.error.href }, {
    stage: '받기',
    reason: '도구 받기 실패',
    href: '#/runs/run_5',
  })
  assert.match(zap.error!.cause, /registry-1\.docker\.io/)
  assert.match(zap.fix ?? '', /네트워크를 확인/)
})

test('실행 실패 최근 오류는 단계 "실행"', () => {
  const t: Tool = {
    name: 'k6', image: 'grafana/k6:1.8.1', state: 'recent_error', installed: true, runnable: true, checkedAt: '2026-11-11T10:00:00+09:00',
    lastError: { stage: 'run', reason: '도구 오류', cause: 'exit 107', runId: 'run_4', at: '2026-11-11T09:00:00+09:00' }, fix: '로그를 보세요',
  }
  const [k6] = toolRows([t])
  assert.equal(k6.badge, '최근 오류')
  assert.equal(k6.ok, false)
  assert.equal(k6.error?.stage, '실행')
})

test('Docker에 못 붙으면 설치 여부를 모른다고 보이고 정상이 아니다', () => {
  const t = (name: Tool['name']): Tool => ({
    name, image: 'x', state: 'docker_unavailable', installed: false, runnable: false, checkedAt: '2026-11-11T10:00:00+09:00', lastError: null, fix: 'Docker가 켜져 있는지 확인하세요',
  })
  for (const r of toolRows([t('k6'), t('zap'), t('semgrep')])) {
    assert.equal(r.badge, 'Docker 꺼짐')
    assert.equal(r.ok, false)
    assert.equal(r.installed, '확인 못 함')
  }
})

test('응답에 없는 도구(구성 안 됨)도 줄을 두고 정상으로 보이지 않는다', () => {
  const rows = toolRows(example('ok').filter((t) => t.name !== 'semgrep'))
  assert.equal(rows.length, 3)
  assert.equal(rows[2].name, 'Semgrep')
  assert.equal(rows[2].badge, '구성 안 됨')
  assert.equal(rows[2].ok, false)
})

test('응답을 못 받으면(null) 세 줄 모두 정상 아님', () => {
  for (const r of toolRows(null)) {
    assert.equal(r.ok, false)
    assert.equal(r.badge, '확인 못 함')
  }
})
