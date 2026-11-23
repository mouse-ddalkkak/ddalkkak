// 6 담은 문제 · 묶음 프롬프트 · 7 프롬프트를 화면을 오가도 유지 (utils/promptMemory.ts, -216). cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { promptMemory } from '../src/utils/promptMemory.ts'

const fakeStore = () => {
  const m = new Map<string, string>()
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) }
}

test('결과(6): 담은 문제 · 묶음 프롬프트를 회차별로 저장하고 읽는다', () => {
  const mem = promptMemory(fakeStore())
  assert.deepEqual(mem.result('r1'), { sel: [], prompt: null })
  mem.saveResult('r1', { sel: ['p2', 's1'], prompt: { ids: ['p2', 's1'], text: '① a\n② b' } })
  assert.deepEqual(mem.result('r1'), { sel: ['p2', 's1'], prompt: { ids: ['p2', 's1'], text: '① a\n② b' } })
  assert.deepEqual(mem.result('r2'), { sel: [], prompt: null })
})

test('문제 상세(7): 문제별 마지막 프롬프트를 저장하고 읽는다', () => {
  const mem = promptMemory(fakeStore())
  assert.equal(mem.issue('r1', 'p2'), null)
  mem.saveIssue('r1', 'p2', '고쳐 주세요\n요구사항')
  assert.equal(mem.issue('r1', 'p2'), '고쳐 주세요\n요구사항')
  assert.equal(mem.issue('r1', 's1'), null)
  assert.equal(mem.issue('r2', 'p2'), null)
})

test('저장소가 없거나 throw하거나 값이 깨져도 빈 값으로 동작한다', () => {
  const broken = { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('quota') } }
  for (const store of [null, broken]) {
    const mem = promptMemory(store)
    mem.saveResult('r1', { sel: ['p2'], prompt: null })
    mem.saveIssue('r1', 'p2', 'x')
    assert.deepEqual(mem.result('r1'), { sel: [], prompt: null })
    assert.equal(mem.issue('r1', 'p2'), null)
  }
  const bad = fakeStore()
  bad.setItem('ddalkkak.prompt.r1', '{깨짐')
  assert.deepEqual(promptMemory(bad).result('r1'), { sel: [], prompt: null })
})
