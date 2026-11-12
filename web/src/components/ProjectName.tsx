import { useState } from 'react'
import { renameRun } from '../api/runs'
import { useApp } from '../app/AppContext'
import { PROJECT_NAME_MAX, cleanProjectName } from '../utils/projectName'
import Input from './Input'
import s from './ProjectName.module.css'

/** 8 · 6 프로젝트 이름 + ✎ — 눌러서 그 자리에서 고친다 (F-HIST-05). 입력칸에서 ENTER · 확인 버튼은 폼 제출이고, 취소는 버튼으로만 한다
 *  (화면 단축키는 두지 않는다 · web.md, -212). 8의 행은 통째로 눌리는 칸이라 안쪽 클릭은 행으로 올라가지 않게 막는다 */
export default function ProjectName({ runId, name, onSaved, className }: { runId: string; name: string; onSaved: (name: string) => void; className?: string }) {
  const { showMessage } = useApp()
  const [draft, setDraft] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const save = async () => {
    if (draft === null) return
    const next = cleanProjectName(draft)
    if (!next) return showMessage(`이름은 1~${PROJECT_NAME_MAX}자로 적어 주세요`)
    if (next === name) return setDraft(null)
    setSaving(true)
    try {
      const r = await renameRun(runId, next)
      onSaved(r.projectName)
      setDraft(null)
      showMessage('이름을 바꿨어요')
    } catch {
      // 래퍼가 문구를 띄웠다 — 입력은 그대로 두고 다시 고칠 수 있게 한다
    }
    setSaving(false)
  }

  if (draft === null) {
    return (
      <span className={`${s.view} ${className ?? ''}`}>
        <span className={s.name}>{name}</span>
        <button
          type="button"
          className={s.edit}
          aria-label="프로젝트 이름 고치기"
          onClick={(e) => {
            e.stopPropagation()
            setDraft(name)
          }}
        >
          ✎
        </button>
      </span>
    )
  }
  return (
    <form
      className={s.form}
      tabIndex={-1} // 입력칸 앞 `>` · 버튼 사이 틈을 눌러도 포커스가 폼에 남아(행으로 가지 않아) 끌어서 행 위에 놓아도 넘어가지 않는다
      onClick={(e) => e.stopPropagation()}
      onSubmit={(e) => {
        e.preventDefault()
        void save()
      }}
    >
      {/* maxLength를 두지 않는다 — 붙여 넣은 긴 이름을 말없이 잘라 저장하지 않고 cleanProjectName이 거절 문구를 띄우게 */}
      <Input autoFocus value={draft} disabled={saving} onChange={(e) => setDraft(e.target.value)} aria-label="프로젝트 이름" />
      <button type="submit" className={s.ok} disabled={saving}>
        확인
      </button>
      <button type="button" className={s.cancel} disabled={saving} onClick={() => setDraft(null)}>
        취소
      </button>
    </form>
  )
}
