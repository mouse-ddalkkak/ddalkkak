import { useState } from 'react'
import { useApp } from '../app/AppContext'
import { runChip } from '../pages/home/homeState'
import ScanDialog from './ScanDialog'
import s from './TopBar.module.css'

/**
 * 상단 바 검사 상태 칩 (-214) — AI 표시 바로 옆. 검사 전 → "검사 시작" 팝업(지금 상태 · 갈 곳, -245) · 검사 중 → 그 회차 5 · 검사 완료 → 그 회차 6.
 * 값은 상단 바가 받아 둔 GET /api/home의 run (-234)
 */
export default function RunChip() {
  const { home } = useApp()
  const [open, setOpen] = useState(false)
  if (!home) return null
  // 상단 바는 화면이 바뀔 때마다 다시 그려져 location.hash가 늘 지금 주소다
  const chip = runChip(home.service, home.run, home.lastRun?.runId ?? null, location.hash)
  const inner = (
    <>
      <span className={s.aiDot} />
      {chip.label}
    </>
  )
  return (
    <>
      {chip.href ? (
        <a href={chip.href} className={s.runChip} data-state={chip.state}>
          {inner}
        </a>
      ) : (
        <button type="button" className={s.runChip} data-state={chip.state} onClick={() => setOpen(true)}>
          {inner}
        </button>
      )}
      {/* 칩이 "검사 중"으로 바뀌어도 팝업(busy 포함)은 남는다 — 분기 안에 두면 open이 true로 남아 검사가 끝난 뒤 혼자 다시 뜬다 (!145 리뷰) */}
      {open && <ScanDialog onClose={() => setOpen(false)} />}
    </>
  )
}
