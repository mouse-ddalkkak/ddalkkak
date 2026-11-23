// 7 관측 근거 원문 로그 — GET /api/runs/{id}/logs 전체에서 이 문제 itemKey 구간만 (F-RES-06) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { logSection } from '../src/utils/logSection.ts'

// openapi.yaml getRunLogs 예시 그대로
const LOGS = `### k6
== perf-1 GET /api/orders · exit=0
running (1m00.0s), 000/200 VUs, 12000 complete and 0 interrupted iterations
http_req_duration..............: avg=212.4ms p(95)=487.2ms
### auth
== sec-3 · 권한 점검
[auth] 로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림
`

test('itemKey 머리줄부터 다음 도구 머리줄 전까지', () => {
  assert.deepEqual(logSection(LOGS, 'perf-1'), [
    '== perf-1 GET /api/orders · exit=0',
    'running (1m00.0s), 000/200 VUs, 12000 complete and 0 interrupted iterations',
    'http_req_duration..............: avg=212.4ms p(95)=487.2ms',
  ])
})

test('마지막 구간은 끝까지 · 빈 줄은 뺀다', () => {
  assert.deepEqual(logSection(LOGS, 'sec-3'), ['== sec-3 · 권한 점검', '[auth] 로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림'])
})

test('같은 도구의 다음 시나리오 머리줄에서 멈춘다 · perf-1이 perf-10을 잡지 않는다', () => {
  const two = '### k6\n== perf-1 GET /a · exit=0\nA\n== perf-10 GET /b · exit=0\nB\n'
  assert.deepEqual(logSection(two, 'perf-1'), ['== perf-1 GET /a · exit=0', 'A'])
  assert.deepEqual(logSection(two, 'perf-10'), ['== perf-10 GET /b · exit=0', 'B'])
})

test('없는 itemKey면 빈 배열', () => {
  assert.deepEqual(logSection(LOGS, 'code-1'), [])
})

test('Semgrep처럼 키를 쉼표로 묶은 머리줄은 그 안의 키마다 잡는다', () => {
  const semgrep = '### semgrep\n== code-1,code-2 · exit=0\nscan\n'
  assert.deepEqual(logSection(semgrep, 'code-1'), ['== code-1,code-2 · exit=0', 'scan'])
  assert.deepEqual(logSection(semgrep, 'code-2'), ['== code-1,code-2 · exit=0', 'scan'])
  assert.deepEqual(logSection(semgrep, 'code-3'), [])
})

test('다시 시도해 같은 키 머리줄이 또 나오면 마지막 시도 구간만 (서버 scenarioTail과 같게)', () => {
  // 서버는 ### 를 파일마다 한 번만 쓰고, 다시 시도는 같은 k6.log에 == 머리줄만 또 붙인다 (RawResultWriter APPEND · K6Runner 멈춤 머리줄)
  const retried = '### k6\n== perf-1 GET /a · exit=-1 · 멈춤\nOLD\n== perf-1 GET /a · exit=0\nNEW\n'
  assert.deepEqual(logSection(retried, 'perf-1'), ['== perf-1 GET /a · exit=0', 'NEW'])
})

test('ZAP 시나리오 뒤에 붙은 관리자 점검(== {key} · 권한 점검)은 다시 시도가 아니라 ZAP 구간에 이어 붙인다', () => {
  const zapAdmin = '### zap\n== sec-1 GET /api/items · exit=0\nZAP-ALERT\n== sec-1 · 권한 점검\n[auth] x\n'
  assert.deepEqual(logSection(zapAdmin, 'sec-1'), ['== sec-1 GET /api/items · exit=0', 'ZAP-ALERT', '== sec-1 · 권한 점검', '[auth] x'])
})
