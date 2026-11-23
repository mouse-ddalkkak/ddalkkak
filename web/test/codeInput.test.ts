// 0-3 ④ 고른 방법 → POST /api/code-source 본문 · ZIP 크기 · 실행 파일 후보 (F-CODE-01 · F-CODE-05 · openapi uploadCodeZip · getCodeArtifacts). cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { artifactLine, artifactNote, canConnect, codeInput, defaultArtifact, initialPick, zipProblem, ZIP_MAX } from '../src/pages/code-source/codeInput.ts'
import type { CodeArtifacts, CodeSource } from '../src/api/codeSource.ts'

const empty = { repo: '', branch: '', token: '', path: '', jar: '', zip: null }

// 예시는 openapi에서 뽑은 contracts 그대로 (python scripts/screen_contracts.py)
const onb = JSON.parse(readFileSync(new URL('../../contracts/4-screen/0-3-onboarding.json', import.meta.url), 'utf8'))
const artifacts = (name: string): CodeArtifacts => onb.calls['GET /api/code-source/artifacts'].responses[`200 ${name}`]

test('코드 없이 진행', () => {
  assert.deepEqual(codeInput('none', empty), { type: 'none' })
})

test('"연결": ZIP은 파일을 골라야, 나머지는 본문이 있어야 누를 수 있다', () => {
  assert.equal(canConnect(null, empty), false)
  assert.equal(canConnect('zip', empty), false)
  assert.equal(canConnect('git', empty), false)
  assert.equal(canConnect('jar', empty), false)
  assert.equal(canConnect('git', { ...empty, repo: 'github.com/a/b' }), true)
  assert.equal(canConnect('none', empty), true)
})

test('GitHub: 주소에 https://를 붙이고, 빈 브랜치 · 토큰은 null', () => {
  assert.deepEqual(codeInput('git', { ...empty, repo: ' github.com/owner/repo ' }), {
    type: 'github',
    ref: 'https://github.com/owner/repo',
    branch: null,
    token: null,
  })
  assert.deepEqual(codeInput('git', { ...empty, repo: 'https://gitlab.com/a/b', branch: 'dev', token: 'glpat-x' }), {
    type: 'github',
    ref: 'https://gitlab.com/a/b',
    branch: 'dev',
    token: 'glpat-x',
  })
  assert.equal(codeInput('git', empty), null)
})

test('실행 파일은 후보에서 고른 path를, 내 컴퓨터는 적은 경로를 ref로 보낸다', () => {
  assert.deepEqual(codeInput('jar', { ...empty, jar: '/home/me/app.jar' }), { type: 'jar', ref: '/home/me/app.jar' })
  // 실행 파일 카드에는 경로 입력칸이 없다 — 내 컴퓨터 칸에 적은 값은 실행 파일로 가지 않는다
  assert.equal(codeInput('jar', { ...empty, path: '/home/me/app.jar' }), null)
  assert.deepEqual(codeInput('local', { ...empty, path: ' /home/me/order-api ' }), { type: 'local', ref: '/home/me/order-api' })
  assert.equal(codeInput('local', empty), null)
})

test('ZIP은 이 본문이 아니라 업로드로 간다', () => {
  assert.equal(codeInput('zip', empty), null)
})

test('ZIP: .zip이 아니거나 200MB를 넘으면 올리기 전에 막는다', () => {
  assert.equal(zipProblem({ name: 'code.zip', size: 10 }), null)
  assert.equal(zipProblem({ name: 'code.zip', size: ZIP_MAX }), null)
  assert.match(zipProblem({ name: 'code.zip', size: ZIP_MAX + 1 }) ?? '', /200MB/)
  assert.match(zipProblem({ name: 'code.tar.gz', size: 10 }) ?? '', /ZIP/)
})

test('실행 파일 후보 줄: 파일 이름 · 실행 중/작업 폴더 · 크기 · 수정 시각(오늘이면 "오늘")', () => {
  const [run, war, locked] = artifacts('found').artifacts
  assert.equal(artifactLine(run, '2026-11-11'), 'order-api.jar · 실행 중 · 46MB · 오늘 09:40')
  assert.equal(artifactLine(war, '2026-11-11'), 'order-api-plain.war · 작업 폴더 · 9MB · 11/10 18:02')
  // 크기 · 시각을 모르는 행(읽을 권한 없음)은 그 칸을 뺀다
  assert.equal(artifactLine(locked, '2026-11-11'), 'old.jar · 작업 폴더')
})

test('실행 파일 기본 선택: 저장한 파일 → 실행 중인 파일 → 고를 수 있는 첫 파일. 읽을 수 없는 행은 고르지 않는다', () => {
  const list = artifacts('found').artifacts
  assert.equal(defaultArtifact(list, null), '/home/me/order-api/build/libs/order-api.jar')
  assert.equal(defaultArtifact(list, '/home/me/order-api/build/libs/order-api-plain.war'), '/home/me/order-api/build/libs/order-api-plain.war')
  assert.equal(defaultArtifact(list, '/home/me/order-api/deploy/old.jar'), '/home/me/order-api/build/libs/order-api.jar')
  assert.equal(defaultArtifact(list.filter((a) => a.from !== 'running'), null), '/home/me/order-api/build/libs/order-api-plain.war')
  assert.equal(defaultArtifact([list[2]], null), null)
})

test('실행 파일 안내: 옛 스타터킷(workDir null) → 새 버전 안내, 후보 없음 → "찾은 JAR · WAR가 없어요"', () => {
  assert.equal(artifactNote(artifacts('oldKit')), '스타터킷을 새 버전으로 올리면 후보를 찾아요')
  assert.equal(artifactNote({ workDir: '/home/me/order-api', artifacts: [] }), '찾은 JAR · WAR가 없어요')
  assert.equal(artifactNote(artifacts('found')), null)
})

const saved = (type: CodeSource['type'], ref: string | null, branch: string | null = null): CodeSource => ({
  type, ref, branch, lastCommit: null, lastCommitAt: null, lastFetchedAt: null, sameVersion: null, decompiled: false, fileHash: null,
})

test('저장한 방식이 있으면 그 카드가 골라진 채로 연다 (홈 "바꾸기"로 왔을 때)', () => {
  assert.deepEqual(initialPick(saved('github', 'https://github.com/owner/order-api', 'main')), { sel: 'git', fields: { repo: 'https://github.com/owner/order-api', branch: 'main' } })
  assert.deepEqual(initialPick(saved('jar', '/home/me/a.jar')), { sel: 'jar', fields: { jar: '/home/me/a.jar' } })
  assert.deepEqual(initialPick(saved('local', '/home/me/order-api')), { sel: 'local', fields: { path: '/home/me/order-api' } })
  assert.deepEqual(initialPick(saved('zip', 'code.zip')), { sel: 'zip', fields: {} })
  assert.deepEqual(initialPick(saved('none', null)), { sel: 'none', fields: {} })
  assert.deepEqual(initialPick(null), { sel: null, fields: {} })
})
