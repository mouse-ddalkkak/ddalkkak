import { getSetup } from '../../api/setup'
import { useLoad } from '../../hooks/useLoad'
import s from './Intro.module.css'

// 0-0 소개 — 첫 설치 때만 (시작 가드가 needsAck: true면 보낸다).
// 비밀번호를 이미 확인했으면(needsAck: false) URL로 들어와도 버튼이 0-1 대신 0-2로 간다 (-212)
// 햄스터 연출은 Layout 하단 밴드의 HamsterScene이 data-ham-target 버튼을 찾아 맞춘다
export default function Intro() {
  const acked = useLoad(getSetup, []).data?.needsAck === false
  const start = () => (location.hash = acked ? '#/login' : '#/setup')
  return (
    <div className={s.wrap}>
      <div className={s.left}>
        <span className={s.kicker}>AI SERVICE CHECK</span>
        <h1 className={s.title}>DDalKKaK</h1>
      </div>
      <div className={s.right}>
        <div className={s.stage}>
          <span className={s.shadow} />
          <span className={s.baseSide} />
          <span className={s.baseTop} />
          <span className={s.hole} />
          <div className={s.clip}>
            <button type="button" data-ham-target className={s.btn} onClick={start} aria-label="시작하기">
              <span className={s.btnSide} />
              <span className={s.btnTop} />
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
