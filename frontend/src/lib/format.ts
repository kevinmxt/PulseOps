export function formatLatency(durationMs: number | null): string {
  return durationMs === null ? '—' : `${durationMs} ms`
}

export function formatAbsoluteTime(iso: string | null, timeZone?: string): string {
  if (!iso) {
    return '—'
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).formatToParts(date)
  const value = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')} ${value('hour')}:${value('minute')}:${value('second')}`
}

export function formatRelativeTime(iso: string | null, now = Date.now()): string {
  if (!iso) {
    return '从未'
  }
  const time = new Date(iso).getTime()
  if (Number.isNaN(time)) {
    return '—'
  }
  const elapsedSeconds = Math.max(0, Math.floor((now - time) / 1000))
  if (elapsedSeconds < 5) {
    return '刚刚'
  }
  if (elapsedSeconds < 60) {
    return `${elapsedSeconds} 秒前`
  }
  const minutes = Math.floor(elapsedSeconds / 60)
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours} 小时前`
  }
  const days = Math.floor(hours / 24)
  if (days < 30) {
    return `${days} 天前`
  }
  return formatAbsoluteTime(iso)
}
