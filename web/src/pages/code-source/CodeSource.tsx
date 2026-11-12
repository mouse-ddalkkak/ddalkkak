import { useEffect, useRef, useState, type ReactNode } from 'react'
import { getCodeArtifacts, getCodeJob, getCodeSource, startCodeSource, uploadCodeZip, type CodeSource as Last } from '../../api/codeSource'
import { ApiError } from '../../api/client'
import { useApp } from '../../app/AppContext'
import { useLoad } from '../../hooks/useLoad'
import { pressable } from '../../utils/keys'
import { artifactLine, artifactNote, canConnect, codeInput, defaultArtifact, initialPick, zipProblem, type Fields, type Key } from './codeInput'
import { codeLine } from '../home/homeState'
import Button from '../../components/Button'
import Input from '../../components/Input'
import Checkbox, { Box } from '../../components/Checkbox'
import { ago } from '../../utils/format'
import s from './CodeSource.module.css'

// 0-3 ④ 소스코드 연결 (screens.md 0-3 ④, -224 · -245). 예전 2-a · 2-b(#/code)를 옮겼다 — 검사를 시작할 때는 이 화면을 거치지 않는다
// 고른 방법 → POST /api/code-source(ZIP은 /zip 업로드) → 작업 1초 폴링 → "✓ 연결됨". 성공한 방식이 저장한 방식이 되고, 검사 준비가 그 방식으로 다시 가져온다
type Option = { key: Key; title: string; lines?: string[]; body?: ReactNode }

const JOB_POLL_MS = 1000 // docs/web.md §3
// 작업 단계 (openapi CodeJob.step)
const STEP: Record<string, string> = { cloning: '저장소 받는 중…', extracting: '압축 푸는 중…', decompiling: '실행 파일 복원 중…', linking: '코드 연결 중…' }

export default function CodeConnect() {
  const { data, setData } = useLoad(getCodeSource, [])
  if (!data) return null
  return <Picker last={data.last} onSaved={() => getCodeSource().then(setData, () => {})} />
}

/** "✓ GitHub owner/order-api · main · 2시간 전 커밋 a3f9c1 · 실행 중인 버전과 일치 ✓" */
function savedLine(last: Last) {
  return [
    `✓ ${codeLine(last)}`,
    last.lastCommitAt && `${ago(last.lastCommitAt)} 커밋 ${last.lastCommit ?? ''}`.trim(),
    last.sameVersion === true ? '실행 중인 버전과 일치 ✓' : last.sameVersion === false && '실행 중인 버전과 달라요',
  ]
    .filter(Boolean)
    .join(' · ')
}

function Picker({ last, onSaved }: { last: Last | null; onSaved: () => void }) {
  const { showMessage, home } = useApp()
  const pick = initialPick(last)
  const [sel, setSel] = useState<Key | null>(pick.sel)
  const [f, setF] = useState<Fields>({ repo: '', branch: '', token: '', path: '', jar: '', zip: null, ...pick.fields })
  const set = (patch: Partial<Fields>) => setF((x) => ({ ...x, ...patch }))
  const [busy, setBusy] = useState(false)
  const [step, setStep] = useState<string | null>(null)
  const [jobError, setJobError] = useState<string | null>(null)
  const [linked, setLinked] = useState(false)
  const [dragging, setDragging] = useState(false)

  // 실행 파일 후보 (F-CODE-05) — 경로 입력칸 없이 이 목록에서만 고른다. 409(연결된 서비스 없음)면 후보 자리에 안내
  const arts = useLoad(getCodeArtifacts, [])
  useEffect(() => {
    if (arts.data) setF((x) => ({ ...x, jar: defaultArtifact(arts.data!.artifacts, x.jar || null) ?? '' }))
  }, [arts.data])
  // "오늘 09:40" — 서버 시각 기준 날짜. 홈을 아직 못 받았으면 이 브라우저 날짜
  const today = home?.health?.serverTime.slice(0, 10) ?? new Date().toLocaleDateString('sv')

  const choose = (key: Key | null) => {
    setSel(key)
    setLinked(false)
  }
  const edit = (patch: Partial<Fields>) => {
    set(patch)
    setLinked(false)
  }

  const gitFields = (
    <>
      <Input aria-label="저장소 주소" value={f.repo} placeholder="github.com/owner/repo" onChange={(e) => edit({ repo: e.target.value })} />
      <div className={s.row}>
        <Input aria-label="브랜치" value={f.branch} placeholder="브랜치 (비우면 기본)" onChange={(e) => edit({ branch: e.target.value })} />
        <Input aria-label="읽기 토큰" type="password" autoComplete="off" value={f.token} placeholder="읽기 토큰 (비공개만)" onChange={(e) => edit({ token: e.target.value })} />
      </div>
    </>
  )
  const note = arts.failed ? '서비스가 연결돼야 후보를 찾아요' : arts.data && artifactNote(arts.data)
  const jarList = (
    <div className={s.picks}>
      {arts.data?.artifacts.map((a) => (
        <button
          key={a.path}
          type="button"
          className={s.pick}
          title={a.path}
          disabled={!a.readable}
          data-on={f.jar === a.path ? '' : undefined}
          onClick={() => {
            choose('jar')
            edit({ jar: a.path })
          }}
        >
          <Box checked={f.jar === a.path} />
          <span className={s.pickText}>{artifactLine(a, today)}</span>
          {a.reason && <span className={s.reason}>{a.reason}</span>}
        </button>
      ))}
      {!arts.data && !arts.failed && <span className={s.line}>후보를 찾는 중…</span>}
      {note && <span className={s.line}>{note}</span>}
    </div>
  )
  const pickZip = (file: File | undefined) => {
    if (!file) return
    const problem = zipProblem(file)
    if (problem) return showMessage(problem)
    choose('zip')
    edit({ zip: file })
  }
  const zipField = (
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
      {f.zip
        ? `${f.zip.name} · ${(f.zip.size / 1024 / 1024).toFixed(1)}MB`
        : last?.type === 'zip'
          ? `✓ 보관한 ${last.ref ?? 'ZIP'} · 바꾸려면 새 ZIP을 끌어 놓기`
          : '폴더를 압축해 끌어다 놓기 · 눌러서 고르기'}
    </label>
  )
  const mounted = useRef(true)
  useEffect(() => () => void (mounted.current = false), [])

  const options: Option[] = [
    { key: 'git', title: 'GitHub / GitLab', body: gitFields, lines: ['비공개면 읽기 토큰 · 검사할 때마다 최신 커밋을 받아요'] },
    { key: 'jar', title: '실행 파일에서 복원 (JAR · WAR)', body: jarList, lines: ['줄 번호·변수명이 원본과 다를 수 있어요 → 결과에 디컴파일 기준 표시'] },
    { key: 'zip', title: 'ZIP 업로드', body: zipField, lines: ['검사할 때마다 이 파일을 다시 풀어요'] },
    {
      key: 'local',
      title: '내 컴퓨터 연결',
      body: <Input aria-label="폴더 경로" value={f.path} placeholder="/home/me/order-api" onChange={(e) => edit({ path: e.target.value })} />,
      lines: ['딸깍 설치 서버의 홈 폴더 아래 절대 경로'],
    },
  ]

  const ready = canConnect(sel, f)

  const connect = async () => {
    if (busy || !ready) return
    setBusy(true)
    setJobError(null)
    try {
      const { jobId } = sel === 'zip' && f.zip ? await uploadCodeZip(f.zip) : await startCodeSource(codeInput(sel, f)!)
      // 코드 작업 1초 폴링: done이면 멈춤 · error면 message · 404면 멈춤 (§3). 코드 없이(jobId null)는 바로 끝
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
          // 실행 중인 버전과 다름 → 경고만 (F-CODE-03)
          if (job.sameVersion === false) showMessage('실행 중인 버전과 달라요')
          break
        }
        await new Promise((r) => setTimeout(r, JOB_POLL_MS))
      }
      if (!mounted.current) return
      setLinked(true)
      onSaved()
    } catch {
      // 작업 error는 위에서 보였고, 그 밖은 래퍼가 띄웠다. 실패하면 저장하지 않는다
    } finally {
      if (mounted.current) {
        setBusy(false)
        setStep(null)
      }
    }
  }

  const card = (o: Option) => (
    <div key={o.key} className={s.card} data-on={sel === o.key ? '' : undefined} {...pressable(() => choose(o.key))}>
      <div className={s.cardHead}>
        <Box checked={sel === o.key} />
        <span className={s.cardTitle}>{o.title}</span>
      </div>
      {o.body && (
        <div className={s.fields} onClick={(e) => e.stopPropagation()} onFocus={() => setSel(o.key)}>
          {o.body}
        </div>
      )}
      {o.lines?.map((t) => (
        <span key={t} className={s.line}>
          {t}
        </span>
      ))}
    </div>
  )

  return (
    <div className={s.wrap}>
      {last && <span className={s.saved}>{savedLine(last)}</span>}
      <div className={s.alts}>{options.map(card)}</div>
      <Checkbox checked={sel === 'none'} onChange={(v) => choose(v ? 'none' : null)} muted className={s.none}>
        코드 없이 진행 — 보안·성능만 검사, 코드 품질은 미검사
      </Checkbox>
      <div className={s.foot}>
        <Button onClick={connect} disabled={busy || !ready}>
          연결
        </Button>
        {busy && step && STEP[step] && <span className={s.step}>{STEP[step]}</span>}
        {linked && !busy && <span className={s.done}>✓ 연결됨</span>}
        {jobError && <span className={s.error}>{jobError}</span>}
      </div>
    </div>
  )
}
