import { useEffect, useRef, useState, type ReactNode } from 'react'
import { getCodeJob, getCodeSource, startCodeSource, uploadCodeZip, type CodeSource as Last } from '../../api/codeSource'
import { startPrepare } from '../../api/runs'
import { ApiError } from '../../api/client'
import { useApp } from '../../app/AppContext'
import { useLoad } from '../../hooks/useLoad'
import { useKeys } from '../../hooks/useKeys'
import { pressable } from '../../utils/keys'
import { codeInput, zipProblem, type Fields, type Key } from './codeInput'
import PageTitle from '../../components/PageTitle'
import Button from '../../components/Button'
import Input from '../../components/Input'
import Checkbox, { Box } from '../../components/Checkbox'
import { ago } from '../../utils/format'
import s from './CodeSource.module.css'

// 2-a · 2-b 코드 연결 — 한 페이지(#/code). GET /api/code-source의 last가 null이면 2-a(첫 검사), 있으면 2-b
// 고른 방법 → POST /api/code-source(ZIP은 /zip 업로드) → 작업 1초 폴링 → POST /api/runs/prepare → 3
type Option = { key: Key; title: string; badge?: string; lines?: string[]; body?: ReactNode }

const TYPE_NAME: Record<string, string> = { github: 'GitHub', jar: '실행 파일', zip: 'ZIP', local: '내 컴퓨터' }
const short = (ref: string | null) => (ref ?? '').replace(/^https?:\/\/(www\.)?github\.com\//, '')

const JOB_POLL_MS = 1000 // docs/web.md §3
// 작업 단계 (openapi CodeJob.step)
const STEP: Record<string, string> = { cloning: '저장소 받는 중…', extracting: '압축 푸는 중…', decompiling: '실행 파일 복원 중…', linking: '코드 연결 중…' }

export default function CodeSource() {
  const { data } = useLoad(getCodeSource, [])
  if (!data) return null
  return <Picker last={data.last} />
}

function Picker({ last }: { last: Last | null }) {
  const first = last === null
  const { showMessage } = useApp()
  const [sel, setSel] = useState<Key | null>(first ? null : 'prev')
  const [f, setF] = useState<Fields>({ repo: '', branch: '', token: '', path: '', zip: null })
  const set = (patch: Partial<Fields>) => setF((x) => ({ ...x, ...patch }))
  const [busy, setBusy] = useState(false)
  const [step, setStep] = useState<string | null>(null)
  const [jobError, setJobError] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)

  // 카드 안 입력칸 — 실행 파일 · 내 컴퓨터는 설치 서버 홈 폴더 아래 절대 경로 (F-CODE-01)
  const pathField = (placeholder: string) => (
    <Input aria-label="경로" value={f.path} placeholder={placeholder} onChange={(e) => set({ path: e.target.value })} />
  )
  const gitFields = (
    <>
      <Input aria-label="저장소 주소" value={f.repo} placeholder="github.com/owner/repo" onChange={(e) => set({ repo: e.target.value })} />
      <div className={s.row}>
        <Input aria-label="브랜치" value={f.branch} placeholder="브랜치 (비우면 기본)" onChange={(e) => set({ branch: e.target.value })} />
        <Input aria-label="읽기 토큰" type="password" autoComplete="off" value={f.token} placeholder="읽기 토큰 (비공개만)" onChange={(e) => set({ token: e.target.value })} />
      </div>
    </>
  )
  const pickZip = (file: File | undefined) => {
    if (!file) return
    const problem = zipProblem(file)
    if (problem) return showMessage(problem)
    setSel('zip')
    set({ zip: file })
  }
  const zipField = (hint: string) => (
    <label
      className={s.drop}
      data-on={dragging ? '' : undefined}
      onDragOver={(e) => {
        e.preventDefault()
        setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault()
        setDragging(false)
        pickZip(e.dataTransfer.files[0])
      }}
    >
      <input type="file" accept=".zip" className={s.file} onChange={(e) => pickZip(e.target.files?.[0])} />
      {f.zip ? `${f.zip.name} · ${(f.zip.size / 1024 / 1024).toFixed(1)}MB` : hint}
    </label>
  )
  const mounted = useRef(true)
  useEffect(() => () => void (mounted.current = false), [])

  const featured: Option = first
    ? { key: 'jar', title: '실행 파일에서 복원 (디컴파일)', badge: '추천 · 설정 없음', lines: ['줄 번호·변수명이 원본과 다를 수 있어요 → 결과에 디컴파일 기준 표시'] }
    : {
        key: 'prev',
        title: `이전 설정과 동일 · ${TYPE_NAME[last.type] ?? last.type} ${short(last.ref)}`,
        badge: '최신 확인됨',
        lines: [
          [
            last.branch,
            last.lastCommitAt && `${ago(last.lastCommitAt)} 커밋 ${last.lastCommit ?? ''}`.trim(),
            last.sameVersion === true ? '실행 중인 버전과 일치 ✓' : last.sameVersion === false && '실행 중인 버전과 달라요',
          ]
            .filter(Boolean)
            .join(' · '),
        ],
      }
  const alts: Option[] = first
    ? [
        { key: 'git', title: 'GitHub / GitLab', body: gitFields, lines: ['비공개면 읽기 토큰 · 재검사 때 자동 최신화'] },
        { key: 'zip', title: 'ZIP 업로드', body: zipField('폴더를 압축해 끌어다 놓기 · 눌러서 고르기') },
        { key: 'local', title: '내 컴퓨터 연결', body: pathField('/home/me/order-api') },
      ]
    : [
        { key: 'jar', title: '실행 파일에서 복원', body: pathField('/home/me/order-api.jar'), lines: ['줄 번호·변수명 부정확 · 디컴파일 기준 표시'] },
        { key: 'git', title: '다른 저장소 / 브랜치', body: gitFields },
        { key: 'zip', title: 'ZIP 업로드', body: zipField('폴더를 압축해서 · 이번 검사에만'), lines: [] },
        { key: 'local', title: '내 컴퓨터 연결', body: pathField('/home/me/order-api') },
      ]
  if (first) featured.body = pathField('/home/me/order-api.jar')

  const body = codeInput(sel, f, last)
  const ready = sel === 'zip' ? !!f.zip : !!body

  const next = async () => {
    if (busy || !ready) return
    setBusy(true)
    setJobError(null)
    try {
      const { jobId } = sel === 'zip' && f.zip ? await uploadCodeZip(f.zip) : await startCodeSource(body!)
      // 코드 작업 1초 폴링: done이면 멈춤 · error면 message · 404면 멈춤 (§3)
      while (jobId && mounted.current) {
        let job
        try {
          job = await getCodeJob(jobId)
        } catch (e) {
          if (e instanceof ApiError && e.status === 404) throw e
          job = null
        }
        if (job) setStep(job.step)
        if (job?.error) {
          setJobError(job.error.message)
          throw new Error(job.error.code)
        }
        if (job?.done) {
          // 실행 중인 버전과 다름 → 경고만, 진행은 가능 (F-CODE-03)
          if (job.sameVersion === false) showMessage('실행 중인 버전과 달라요')
          break
        }
        await new Promise((r) => setTimeout(r, JOB_POLL_MS))
      }
      if (!mounted.current) return
      const { runId } = await startPrepare(jobId)
      location.hash = `#/prepare/${encodeURIComponent(runId)}`
    } catch {
      if (mounted.current) {
        setBusy(false)
        setStep(null)
      }
    }
  }
  // ESC 취소(홈) · ENTER 다음 (F-HOME-05)
  useKeys({ Escape: () => (location.hash = '#/'), Enter: next })

  const card = (o: Option, big = false) => (
    <div key={o.key} className={`${s.card} ${big ? s.featured : ''}`} data-on={sel === o.key ? '' : undefined} {...pressable(() => setSel(o.key))}>
      <div className={s.cardHead}>
        <Box checked={sel === o.key} />
        <span className={s.cardTitle}>{o.title}</span>
        {o.badge && <span className={s.badge}>{o.badge}</span>}
      </div>
      {o.body && (
        <div className={s.fields} onClick={(e) => e.stopPropagation()} onFocus={() => setSel(o.key)}>
          {o.body}
        </div>
      )}
      {o.lines?.map((t) => (
        <span key={t} className={big ? s.lineBig : s.line}>
          {t}
        </span>
      ))}
    </div>
  )

  return (
    <div className={s.wrap}>
      <PageTitle
        title={first ? '소스코드는 어디에 있나요?' : '이번 검사에 쓸 소스코드'}
        sub={first ? '첫 검사예요. 한 번 정하면 다음부터는 "이전 설정과 동일"로 바로 넘어가요' : "코드가 있어야 코드 품질 검사와 '관련 코드' 표시가 돼요"}
      />
      {card(featured, true)}
      <div className={s.divider}>
        <span className={s.dots} />
        <span>{first ? '더 정확하게 — 원본 연결' : '다른 방법'}</span>
        <span className={s.dots} />
      </div>
      <div className={s.alts} style={{ gridTemplateColumns: `repeat(${alts.length === 3 ? 3 : 2},minmax(0,1fr))` }} data-first={first ? '' : undefined}>
        {alts.map((o) => card(o))}
      </div>
      <Checkbox checked={sel === 'none'} onChange={(v) => setSel(v ? 'none' : null)} muted className={s.none}>
        코드 없이 진행 — 보안·성능만 검사, 코드 품질은 미검사
      </Checkbox>
      {jobError && <span className={s.error}>{jobError}</span>}
      {busy && step && STEP[step] && <span className={s.step}>{STEP[step]}</span>}
      <div className={s.foot}>
        <Button variant="text" arrow={false} onClick={() => (location.hash = '#/')}>
          ← 취소 (홈)
        </Button>
        <span className={s.grow} />
        <Button onClick={next} disabled={busy || !ready}>
          {sel === 'prev' ? '이전 설정과 동일 · 다음' : '다음'}
        </Button>
      </div>
    </div>
  )
}
