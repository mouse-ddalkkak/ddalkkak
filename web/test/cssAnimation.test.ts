// CSS 모듈은 animation 이름을 바꿔(_ttk-walk_s70d5_1) styles/tokens.css의 전역 @keyframes를 못 찾는다 → 별 · 햄스터 · 로켓이 멈춤 (-214)
// 모듈 CSS에서 ttk-* 애니메이션은 global(ttk-…)로 써야 한다 — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const modules = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? modules(join(dir, e.name)) : e.name.endsWith('.module.css') ? [join(dir, e.name)] : [],
  )

test('모듈 CSS의 ttk-* 애니메이션 이름은 global()로 감싼다', () => {
  const bad = modules('src').flatMap((file) =>
    [...readFileSync(file, 'utf8').matchAll(/animation(?:-name)?:[^;}]*/g)]
      .filter((m) => /(?<![\w(-])ttk-[a-z]+\b(?!\))/.test(m[0]))
      .map((m) => `${file}: ${m[0]}`),
  )
  assert.deepEqual(bad, [])
})
