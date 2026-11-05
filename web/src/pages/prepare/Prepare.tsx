import { useEffect, useRef, useState } from 'react'
import { getPrepare, skipToDefaultScope, type Prepare as PrepareData, type PrepareRow } from '../../api/runs'
import { ApiError } from '../../api/client'
import { usePolling } from '../../hooks/usePolling'
import { useKeys } from '../../hooks/useKeys'
import { useApp } from '../../app/AppContext'
import PageTitle from '../../components/PageTitle'
import Panel from '../../components/Panel'
import Gauge from '../../components/Gauge'
import Led from '../../components/Led'
import Button from '../../components/Button'
import { HamsterSprite } from '../../components/HamsterWalk'
import { prepareNext } from './prepareNext'
import { diffLines } from './apiDiff'
import s from './Prepare.module.css'

// 3 검사 범위 분석 — GET /api/runs/prepare/{id} 1초마다 (docs/web.md §3)
// done && error null → 멈추고 4로 · error → 멈추고 message · 404 → 멈추고 #/code
// "기본 범위로 바로 가기" → POST …/use-default. done이면 바로 4, 아니면(API 목록 받는 중) 응답을 그리고 폴링을 이어 간다. 409 PREPARE_ENDED도 폴링이 정리한다
const STATE_TEXT: Record<PrepareRow['state'], string> = { done: '완료', running: '진행 중', waiting: '대기', skipped: '건너뜀' }

export default function Prepare({ runId }: { runId: string }) {
  const { ai } = useApp()
  const [data, setData] = useState<PrepareData>()
  const [skipping, setSkipping] = useState(false)
  // 응답이 늦게(최대 140초) 와도 화면을 떠났거나 회차가 바뀌었으면 버린다
  const current = useRef<string | null>(runId)
  useEffect(() => {
    current.current = runId
    return () => {
      current.current = null
    }
  }, [runId])
  const scope = `#/prepare/${encodeURIComponent(runId)}/scope`

  usePolling(
    async (alive) => {
      try {
        const d = await getPrepare(runId)
        if (!alive()) return true
        setData(d)
        const next = prepareNext(d)
        if (next === 'scope') location.hash = scope
        return next !== 'poll'
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) {
          if (alive()) location.hash = '#/code'
          return true
        }
        throw e
      }
    },
    1000,
    [runId],
  )

  // 키 없는 호출이라 AI 서버가 답하지 않으면 최대 140초 걸린다 → 누른 뒤에는 비활성
  const skip = async () => {
    setSkipping(true)
    try {
      const d = await skipToDefaultScope(runId)
      if (current.current !== runId) return
      setData(d)
      if (prepareNext(d) === 'scope') location.hash = scope
    } catch (e) {
      // 409 PREPARE_ENDED · 404: 폴링이 이어서 4 · 실패 · #/code로 보낸다. 그 밖(네트워크 끊김 등)은 다시 누를 수 있게 푼다
      if (!(e instanceof ApiError && (e.status === 409 || e.status === 404)) && current.current === runId) setSkipping(false)
    }
  }

  // ESC 뒤로(2-a · 2-b) · ENTER 기본 범위로 바로 가기
  useKeys({
    Escape: () => (location.hash = '#/code'),
    Enter: () => !data?.error && !skipping && skip(),
  })

  // 제목: AI 모드는 응답의 aiMode가 정하고, 응답 전에는 헤더 AI 표시를 따른다
  const llm = data ? data.aiMode === 'llm' : !!ai?.connected
  return (
    <div className={s.wrap}>
      <PageTitle
        center
        title={llm ? 'AI가 딸깍을 준비 중입니다' : '딸깍을 준비 중입니다'}
        sub={llm ? '코드를 읽고 이 서비스에 맞는 검사 시나리오를 짜고 있어요' : '코드를 읽고 기본 검사 범위를 정리하고 있어요'}
      >
        <HamsterSprite height={168} />
      </PageTitle>
      {data && (
        <Panel className={s.panel}>
          {data.rows.map((r) => (
            <div key={r.name} className={s.rowGroup}>
              <div className={s.row}>
                {r.state === 'running' ? <Led color="cyan-300" /> : <span className={s.sq} data-state={r.state} />}
                <span className={s.rowText}>
                  <span className={s.rowName} data-state={r.state}>
                    {r.name}
                  </span>
                  {r.detail && <span className={s.detail}>{r.detail}</span>}
                </span>
                <span className={s.state} data-state={r.state}>
                  {STATE_TEXT[r.state]}
                </span>
              </div>
              {/* 코드 가져오기는 코드 연결 때만 있어 순서가 아니라 줄 이름으로 찾는다 */}
              {r.name === 'API 목록 받기' && (
                <ul className={s.diff}>
                  {diffLines(data.apiDiff).map((d) => (
                    <li key={d.api}>
                      <span className={s.diffApi}>{d.api}</span> {d.text}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
          <div className={s.bar}>
            <div className={s.gauge}>
              <Gauge pct={data.pct} cells={20} color="var(--cyan-300)" tall />
            </div>
            <span className={s.pct}>{data.pct}%</span>
          </div>
        </Panel>
      )}
      <div className={s.foot}>
        {data?.error ? (
          <span className={s.error}>{data.error.message}</span>
        ) : (
          <span className={s.caption}>끝나면 자동으로 검사 범위 화면으로 넘어가요</span>
        )}
        <span className={s.grow} />
        {!data?.error && (
          <Button variant="text" arrow={false} className={s.skip} disabled={skipping} onClick={skip}>
            AI 추천 없이 기본 범위로 바로 가기
          </Button>
        )}
      </div>
    </div>
  )
}
