import { describe, expect, it } from 'vitest'
import {
  checkResultClass,
  checkResultLabel,
  lastKnownStatusLabel,
  statusClass,
  statusLabel,
  typeLabel,
} from './monitorDisplay'

describe('monitor display labels', () => {
  it('labels enabled monitor statuses', () => {
    expect(statusLabel('UP', true)).toBe('正常')
    expect(statusLabel('DOWN', true)).toBe('故障')
    expect(statusLabel('UNKNOWN', true)).toBe('未知')
  })

  it('prioritises the disabled label over the last known status', () => {
    expect(statusLabel('DOWN', false)).toBe('已禁用')
    expect(statusClass('DOWN', false)).toBe('status-disabled')
    expect(lastKnownStatusLabel('DOWN')).toBe('故障')
  })

  it('maps statuses to badge classes', () => {
    expect(statusClass('UP', true)).toBe('status-up')
    expect(statusClass('DOWN', true)).toBe('status-down')
    expect(statusClass('UNKNOWN', true)).toBe('status-unknown')
  })

  it('labels monitor types', () => {
    expect(typeLabel('HTTP')).toBe('HTTP')
    expect(typeLabel('HEARTBEAT')).toBe('Heartbeat')
  })

  it('labels check results', () => {
    expect(checkResultLabel(true)).toBe('成功')
    expect(checkResultLabel(false)).toBe('失败')
    expect(checkResultClass(true)).toBe('status-up')
    expect(checkResultClass(false)).toBe('status-down')
  })
})
