import { useState } from 'react'
import { getSetup } from '../../api/setup'
import { useLoad } from '../../hooks/useLoad'
import base from '../../assets/img/btn-base.png'
import cap from '../../assets/img/btn-cap.png'
import front from '../../assets/img/btn-front.png'
import s from './Intro.module.css'

// 0-0 소개 — 첫 설치 때만 (시작 가드가 needsAck: true면 보낸다).
// 비밀번호를 이미 확인했으면(needsAck: false) URL로 들어와도 버튼이 0-1 대신 0-2로 간다 (-212)
// 햄스터 연출은 Layout 하단 밴드의 HamsterScene이 data-ham-target 버튼을 찾아 맞춘다
export default function Intro() {
  const acked = useLoad(getSetup, []).data?.needsAck === false
  // 버튼이 눌려 들어간 뒤 0.4초 있다 넘어간다 (Pixel Flow blackHole, -259)
  const [pressed, setPressed] = useState(false)
  const start = () => {
    if (pressed) return
    setPressed(true)
    setTimeout(() => (location.hash = acked ? '#/login' : '#/setup'), 400)
  }
  return (
    <div className={s.wrap}>
      <div className={s.left}>
        <span className={s.kicker}>AI SERVICE CHECK</span>
        <h1 className={s.title}>DDalKKaK</h1>
      </div>
      <div className={s.right}>
        {/* 받침(작은 행성 포함) → 눌리는 캡 → 앞면 순으로 겹친다. 앞면이 캡 아랫부분을 가린다 (-259) */}
        <div className={s.stage}>
          <img src={base} alt="" className={s.layer} />
          <button type="button" data-ham-target className={s.btn} data-pressed={pressed || undefined} onClick={start} aria-label="시작하기">
            <img src={cap} alt="" className={s.layer} />
          </button>
          <img src={front} alt="" className={s.layer} />
        </div>
      </div>
    </div>
  )
}
