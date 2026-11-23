// div 버튼 · 5 오류 팝업 Tab (utils/keys.ts). cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pressable, trapTab } from '../src/utils/keys.ts'

test('pressable: ENTER · 스페이스로 누를 수 있고, 안쪽 버튼에서 온 키는 그 버튼 몫이다', () => {
  let n = 0
  const p = pressable(() => n++)
  const self = {}
  const key = (k: string, target: unknown) => p.onKeyDown({ key: k, target, currentTarget: self, preventDefault() {} })
  key('Enter', self)
  key(' ', self)
  key('Enter', {})
  key('a', self)
  assert.equal(n, 2)
  assert.equal(p.role, 'button')
  assert.equal(p.tabIndex, 0)
})

// 팝업 대신: 버튼 a · b · c와 버튼 아닌 로그 칸을 품은 상자. Run.tsx는 popup div를 그대로 넘긴다
const popup = { querySelectorAll: () => ['a', 'b', 'c'], contains: (n: unknown) => ['a', 'b', 'c', 'log'].includes(n as string) }

test('5 오류 팝업 Tab: 끝 버튼에서는 반대쪽 끝으로, 팝업 밖(body)에서 시작하면 팝업 버튼으로 끌어온다 (-194)', () => {
  assert.equal(trapTab(popup, 'c', false), 'a')
  assert.equal(trapTab(popup, 'a', true), 'c')
  assert.equal(trapTab(popup, 'b', false), null) // 가운데는 브라우저가 옮긴다
  assert.equal(trapTab(popup, 'body', true), 'c') // 팝업 밖에서 Shift+Tab
  assert.equal(trapTab(popup, 'body', false), 'a')
})

test('5 오류 팝업 Tab: 팝업 안이지만 버튼이 아닌 곳(스크롤 생긴 로그 칸)은 팝업이 품었으니 브라우저에 맡긴다 (-194)', () => {
  assert.equal(trapTab(popup, 'log', false), null)
  assert.equal(trapTab(popup, 'log', true), null)
})
