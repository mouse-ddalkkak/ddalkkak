import s from './Switch.module.css'

/** 영역 ON/OFF 스위치 (4 영역 헤더) */
export default function Switch({ on, disabled, onChange }: { on: boolean; disabled?: boolean; onChange: (v: boolean) => void }) {
  return (
    <button type="button" role="switch" aria-checked={on} disabled={disabled} className={s.sw} data-on={on ? '' : undefined} onClick={() => onChange(!on)}>
      <span className={s.knob} />
    </button>
  )
}
