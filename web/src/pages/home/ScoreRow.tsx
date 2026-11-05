import { scoreColor, scoreText } from '../../utils/score'
import s from './ScoreRow.module.css'

/** 이름 · 점선 리더 · 점수. 미검사(null)는 "--" 회색 */
export default function ScoreRow({ name, value }: { name: string; value: number | null }) {
  return (
    <div className={s.row}>
      <span className={s.name} data-none={value == null ? '' : undefined}>
        {name}
      </span>
      <span className={s.leader} />
      <span className={s.score} style={{ color: scoreColor(value) }}>
        {scoreText(value)}
      </span>
    </div>
  )
}
