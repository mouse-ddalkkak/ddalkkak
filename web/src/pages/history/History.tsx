import { getHistory } from '../../api/runs'
import { useLoad } from '../../hooks/useLoad'
import { useKeys } from '../../hooks/useKeys'
import { pressable } from '../../utils/keys'
import PageTitle from '../../components/PageTitle'
import Button from '../../components/Button'
import { AREAS, AREA_NAME, scoreColor, scoreText } from '../../utils/score'
import { dateOnly, shortDateTime } from '../../utils/format'
import s from './History.module.css'

// 8 이력 — 행 → 그 회차의 6 · 재검사 → #/code(2-b)
// 서비스 이름은 GET /api/runs에 없어(/api/home) 부제는 "검사 N번"만. 막대 아래 날짜는 trend.at
export default function History() {
  const { data } = useLoad(getHistory, [])
  // ESC 홈 · ENTER 최신 결과 (F-HOME-05)
  const latest = data?.runs[0]
  useKeys({
    Escape: () => (location.hash = '#/'),
    Enter: () => latest && (location.hash = `#/runs/${encodeURIComponent(latest.runId)}`),
  })
  if (!data) return null

  return (
    <div className={s.wrap}>
      <PageTitle
        title="검사 이력"
        sub={`검사 ${data.total}번`}
        right={
          <Button onClick={() => (location.hash = '#/code')} className={s.rescan}>
            재검사
          </Button>
        }
      />
      <section className={s.trend}>
        {AREAS.map((a) => {
          const vals = data.trend[a]
          const last = vals[vals.length - 1] ?? null
          const prev = [...vals.slice(0, -1)].reverse().find((v) => v != null) ?? null
          const delta = last == null ? null : prev == null ? '첫 측정' : last - prev
          return (
            <div key={a} className={s.card}>
              <div className={s.cardHead}>
                <span className={s.last} style={{ color: scoreColor(last) }}>
                  {scoreText(last)}
                </span>
                <div className={s.cardText}>
                  <span className={s.name}>{AREA_NAME[a]}</span>
                  {delta !== null && (
                    <span
                      className={s.delta}
                      style={{ color: typeof delta === 'string' ? 'var(--ink-400)' : delta >= 0 ? 'var(--green-400)' : 'var(--coral-400)' }}
                    >
                      {typeof delta === 'string' ? delta : delta >= 0 ? `+${delta}` : `−${-delta}`}
                    </span>
                  )}
                </div>
              </div>
              <div className={s.bars} style={{ gridTemplateColumns: `repeat(${vals.length},minmax(0,1fr))` }}>
                <span className={s.goal} />
                {vals.map((v, i) => {
                  const latest = i === vals.length - 1
                  return (
                    <div key={i} className={s.barCol}>
                      <span className={s.barLabel} data-latest={latest ? '' : undefined}>
                        {scoreText(v)}
                      </span>
                      {v == null ? (
                        <span className={s.barNone} />
                      ) : (
                        <span className={s.bar} style={{ height: `${v}%`, background: latest ? scoreColor(v) : 'var(--ink-600)' }} />
                      )}
                      {data.trend.at[i] && <span className={s.barDate}>{dateOnly(data.trend.at[i]).slice(5)}</span>}
                    </div>
                  )
                })}
              </div>
            </div>
          )
        })}
      </section>
      <div className={s.runs}>
        {data.runs.map((r) => (
          <div key={r.runId} className={s.run} {...pressable(() => (location.hash = `#/runs/${encodeURIComponent(r.runId)}`))}>
            <div className={s.runText}>
              <div className={s.runHead}>
                <span className={s.date}>{shortDateTime(r.at)}</span>
                {r.tags.map((t) => (
                  <span key={t} className={s.tag} data-partial={r.status === 'partial' ? '' : undefined}>
                    {t}
                  </span>
                ))}
              </div>
              <span className={s.sub}>
                {r.scenarioCount} 시나리오 · {r.codeSource ?? '코드 없음'}
              </span>
            </div>
            <div className={s.scores}>
              {AREAS.map((a) => (
                <span key={a} className={s.score} data-none={r.scores[a] == null ? '' : undefined} style={{ color: scoreColor(r.scores[a]) }}>
                  {scoreText(r.scores[a])}
                </span>
              ))}
            </div>
            <span className={s.fix}>고칠 점 {r.fixCount}</span>
            <span className={s.open}>
              결과 보기<span className={s.arrow} />
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}
