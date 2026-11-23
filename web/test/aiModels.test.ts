// 0-3 ④ · AI 연결 팝업 모델 셀렉트 (F-AI-01, -213) — cd web && npm test
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CUSTOM, MODELS, modelOptions, modelValue } from '../src/utils/aiModels.ts'

test('Claude · OpenAI는 기본 모델(빈 값)이 맨 앞, "직접 입력"이 맨 뒤인 목록', () => {
  for (const p of ['claude', 'openai'] as const) {
    const opts = modelOptions(p)!
    assert.equal(opts[0].value, '')
    assert.match(opts[0].label, new RegExp(MODELS[p].default.replace('.', '\.')))
    assert.equal(opts.at(-1)!.value, CUSTOM)
    assert.ok(opts.some((o) => o.value === MODELS[p].list[0]))
  }
  assert.deepEqual(MODELS.claude.default, 'claude-haiku-5-5')
  assert.deepEqual(MODELS.openai.default, 'gpt-4o-mini')
})

test('직접 URL은 목록이 없다 (입력칸)', () => {
  assert.equal(modelOptions('custom'), null)
})

test('연결 확인에 보낼 모델: 고른 값 그대로, 기본은 빈 값, 직접 입력은 입력한 글자', () => {
  assert.equal(modelValue('claude-sonnet-5-5', 'x'), 'claude-sonnet-5-5')
  assert.equal(modelValue('', 'x'), '')
  assert.equal(modelValue(CUSTOM, '  my-model '), 'my-model')
})
