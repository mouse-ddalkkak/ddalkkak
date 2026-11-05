import rocket from '../assets/img/rocket.png'
import s from './RocketFleet.module.css'

const SHIPS = [
  { left: '5%', w: 120, dur: 12, delay: 0, op: 1 },
  { left: '86%', w: 100, dur: 14, delay: 4, op: 1 },
  { left: '30%', w: 64, dur: 16, delay: 8, op: 0.55 },
  { left: '64%', w: 76, dur: 13, delay: 10.5, op: 0.7 },
]

/** 아래 → 위로 반복 비행하는 로켓 (0-0 제외) */
export default function RocketFleet() {
  return (
    <div className={s.fleet} aria-hidden>
      {SHIPS.map((x) => (
        <div key={x.left} className={s.ship} style={{ left: x.left, width: x.w, opacity: x.op, animationDuration: `${x.dur}s`, animationDelay: `${x.delay}s` }}>
          <img src={rocket} alt="" className={s.img} />
        </div>
      ))}
    </div>
  )
}
