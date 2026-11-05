import { useEffect, useState } from 'react'

/** 화면에 들어올 때 한 번 불러온다. 떠난 뒤 도착한 응답은 버린다. 실패 문구는 래퍼가 띄운다 */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T>()
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let ignore = false
    fn().then(
      (d) => !ignore && setData(d),
      () => !ignore && setFailed(true),
    )
    return () => {
      ignore = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  return { data, setData, failed }
}
