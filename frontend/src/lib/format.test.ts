import { describe, expect, it } from 'vitest'
import { formatAbsoluteTime, formatLatency, formatRelativeTime } from './format'

const NOW = new Date('2026-10-24T12:00:00Z').getTime()

describe('formatRelativeTime', () => {
  it('shows never for a missing timestamp', () => {
    expect(formatRelativeTime(null, NOW)).toBe('从未')
  })

  it('shows just now for timestamps within five seconds', () => {
    expect(formatRelativeTime('2026-10-24T11:59:58Z', NOW)).toBe('刚刚')
  })

  it('shows seconds, minutes, hours and days', () => {
    expect(formatRelativeTime('2026-10-24T11:59:30Z', NOW)).toBe('30 秒前')
    expect(formatRelativeTime('2026-10-24T11:58:30Z', NOW)).toBe('1 分钟前')
    expect(formatRelativeTime('2026-10-24T10:00:00Z', NOW)).toBe('2 小时前')
    expect(formatRelativeTime('2026-10-21T12:00:00Z', NOW)).toBe('3 天前')
  })

  it('clamps future timestamps to just now', () => {
    expect(formatRelativeTime('2026-10-24T12:05:00Z', NOW)).toBe('刚刚')
  })

  it('falls back to an absolute local date after thirty days', () => {
    expect(formatRelativeTime('2026-08-01T08:00:00Z', NOW)).toMatch(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/,
    )
  })
})

describe('formatAbsoluteTime', () => {
  it('formats in the requested timezone', () => {
    expect(formatAbsoluteTime('2026-10-24T08:00:00Z', 'UTC')).toBe('2026-10-24 08:00:00')
  })

  it('shows a dash for a missing or invalid timestamp', () => {
    expect(formatAbsoluteTime(null)).toBe('—')
    expect(formatAbsoluteTime('not-a-date')).toBe('—')
  })
})

describe('formatLatency', () => {
  it('shows milliseconds for a known duration', () => {
    expect(formatLatency(87)).toBe('87 ms')
  })

  it('shows a dash when latency is unavailable', () => {
    expect(formatLatency(null)).toBe('—')
  })
})
