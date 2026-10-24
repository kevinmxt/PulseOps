import { describe, expect, it } from 'vitest'
import type { Monitor } from '../api/types'
import {
  HEARTBEAT_MONITOR_DEFAULTS,
  heartbeatFormToMonitorInput,
  monitorToHeartbeatFormValues,
  validateHeartbeatForm,
} from './heartbeatInput'

function heartbeatMonitor(overrides: Partial<Monitor> = {}): Monitor {
  return {
    id: 8,
    name: 'Nightly job',
    type: 'HEARTBEAT',
    targetUrl: null,
    method: null,
    intervalSeconds: 300,
    connectTimeoutMs: null,
    responseTimeoutMs: null,
    expectedStatusMin: null,
    expectedStatusMax: null,
    enabled: true,
    failureThreshold: null,
    recoveryThreshold: null,
    heartbeatGraceSeconds: 60,
    currentStatus: 'UP',
    lastCheckedAt: null,
    lastSuccessAt: null,
    lastHeartbeatAt: '2026-10-24T08:00:00Z',
    createdAt: '2026-10-24T00:00:00Z',
    updatedAt: '2026-10-24T00:00:00Z',
    heartbeatToken: null,
    lastCheckDurationMs: null,
    ...overrides,
  }
}

const validValues = {
  name: 'Nightly job',
  intervalSeconds: 300,
  heartbeatGraceSeconds: 60,
  enabled: true,
}

describe('heartbeat form values', () => {
  it('provides defaults for a new heartbeat monitor', () => {
    expect(HEARTBEAT_MONITOR_DEFAULTS).toEqual({
      name: '',
      intervalSeconds: 300,
      heartbeatGraceSeconds: 60,
      enabled: true,
    })
  })

  it('prefills from a heartbeat monitor and defaults a missing grace period', () => {
    expect(monitorToHeartbeatFormValues(heartbeatMonitor())).toEqual(validValues)
    expect(
      monitorToHeartbeatFormValues(heartbeatMonitor({ heartbeatGraceSeconds: null }))
        .heartbeatGraceSeconds,
    ).toBe(60)
  })

  it('builds a heartbeat payload and trims the name', () => {
    expect(heartbeatFormToMonitorInput({ ...validValues, name: '  Nightly job  ' })).toEqual({
      name: 'Nightly job',
      type: 'HEARTBEAT',
      intervalSeconds: 300,
      heartbeatGraceSeconds: 60,
      enabled: true,
    })
  })
})

describe('heartbeat form validation', () => {
  it('accepts a complete valid form', () => {
    expect(validateHeartbeatForm(validValues)).toEqual({})
  })

  it('requires a non-blank name of at most 100 characters', () => {
    expect(validateHeartbeatForm({ ...validValues, name: '  ' }).name).toContain('名称')
    expect(validateHeartbeatForm({ ...validValues, name: 'x'.repeat(101) }).name).toContain('100')
  })

  it('enforces the heartbeat interval range', () => {
    expect(validateHeartbeatForm({ ...validValues, intervalSeconds: 29 }).intervalSeconds).toContain(
      '30',
    )
    expect(
      validateHeartbeatForm({ ...validValues, intervalSeconds: 2592001 }).intervalSeconds,
    ).toContain('2592000')
    expect(
      validateHeartbeatForm({ ...validValues, intervalSeconds: null }).intervalSeconds,
    ).toContain('不能为空')
  })

  it('enforces the grace period range including zero', () => {
    expect(validateHeartbeatForm({ ...validValues, heartbeatGraceSeconds: 0 })).toEqual({})
    expect(
      validateHeartbeatForm({ ...validValues, heartbeatGraceSeconds: -1 }).heartbeatGraceSeconds,
    ).toContain('0')
    expect(
      validateHeartbeatForm({ ...validValues, heartbeatGraceSeconds: 86401 })
        .heartbeatGraceSeconds,
    ).toContain('86400')
  })
})
