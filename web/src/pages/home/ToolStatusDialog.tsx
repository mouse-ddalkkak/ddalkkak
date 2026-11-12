import Dialog from '../../components/Dialog'
import { getTools } from '../../api/home'
import { useLoad } from '../../hooks/useLoad'
import { shortDateTime } from '../../utils/format'
import { toolRows } from './toolStatus'
import s from './ToolStatusDialog.module.css'

/** 홈 메뉴 "도구 상태" 팝업 (F-HOME-01 · -229). 열 때 GET /api/tools 한 번 — k6 · ZAP · Semgrep 고정 세 줄, 정상이 아니면 정상처럼 보이지 않는다 */
export default function ToolStatusDialog({ onClose }: { onClose: () => void }) {
  const { data, failed } = useLoad(getTools, [])
  return (
    <Dialog title="도구 상태" onClose={onClose}>
      {!data && !failed ? (
        <p className={s.muted}>확인 중…</p>
      ) : (
        <div className={s.list}>
          {failed && <p className={s.bad}>도구 상태를 불러오지 못했어요</p>}
          {toolRows(data?.tools ?? null).map((r) => (
            <div key={r.name} className={s.row}>
              <div className={s.head}>
                <span className={s.name}>{r.name}</span>
                {r.image && <span className={s.muted}>{r.image}</span>}
                <span className={s.grow} />
                <span className={s.badge} data-ok={r.ok ? '' : undefined}>
                  {r.badge}
                </span>
              </div>
              <span className={s.muted}>
                설치 {r.installed} · 실행 {r.runnable}
              </span>
              {r.error && (
                <div className={s.error}>
                  <span>
                    최근 오류 · {r.error.stage} · {r.error.reason} · {shortDateTime(r.error.at)} ·{' '}
                    <a href={r.error.href} className={s.link}>
                      그 검사 보기
                    </a>
                  </span>
                  <pre className={s.cause}>{r.error.cause}</pre>
                </div>
              )}
              {r.fix && <span className={s.fix}>조치 · {r.fix}</span>}
            </div>
          ))}
        </div>
      )}
    </Dialog>
  )
}
