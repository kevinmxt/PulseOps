import { describe, expect, it } from 'vitest'
import type { Monitor } from '../api/types'
import {
  HTTP_MONITOR_DEFAULTS,
  formValuesToMonitorInput,
  monitorToFormValues,
  monitorToInput,
  validateHttpMonitorForm,
} from './monitorInput'

function monitor(overrides: Partial<Monitor> = {}): Monitor {
  return {
    id: 4,
    name: 'Public API',
    type: 'HTTP',
    targetUrl: 'http://8.8.8.8/health',
    method: 'GET',
    intervalSeconds: 60,
    connectTimeoutMs: 3000,
    responseTimeoutMs: 10000,
    expectedStatusMin: 200,
    expectedStatusMax: 299,
    enabled: true,
    failureThreshold: 3,
    recoveryThreshold: 2,
    heartbeatGraceSeconds: null,
    currentStatus: 'UP',
    lastCheckedAt: null,
    lastSuccessAt: null,
    lastHeartbeatAt: null,
    createdAt: '2026-10-24T00:00:00Z',
    updatedAt: '2026-10-24T00:00:00Z',
    heartbeatToken: null,
    lastCheckDurationMs: null,
    ...overrides,
  }
}

const validValues = {
  name: 'Public API',
  targetUrl: 'https://1.1.1.1/health',
  method: 'GET' as const,
  intervalSeconds: 60,
  connectTimeoutMs: 3000,
  responseTimeoutMs: 10000,
  expectedStatusMin: 200,
  expectedStatusMax: 299,
  failureThreshold: 3,
  recoveryThreshold: 2,
  enabled: true,
}

describe('monitor form values', () => {
  it('builds a complete HTTP payload and trims text fields', () => {
    expect(
      formValuesToMonitorInput({
        ...validValues,
        name: '  Public API  ',
        targetUrl: '  https://1.1.1.1/health  ',
      }),
    ).toEqual({
      name: 'Public API',
      type: 'HTTP',
      targetUrl: 'https://1.1.1.1/health',
      method: 'GET',
      intervalSeconds: 60,
      connectTimeoutMs: 3000,
      responseTimeoutMs: 10000,
      expectedStatusMin: 200,
      expectedStatusMax: 299,
      enabled: true,
      failureThreshold: 3,
      recoveryThreshold: 2,
    })
  })

  it('prefills form values from an HTTP monitor', () => {
    expect(monitorToFormValues(monitor())).toEqual({
      ...validValues,
      targetUrl: 'http://8.8.8.8/health',
    })
  })

  it('toggles only enabled while preserving every configurable field', () => {
    expect(monitorToInput(monitor(), false)).toEqual({
      name: 'Public API',
      type: 'HTTP',
      targetUrl: 'http://8.8.8.8/health',
      method: 'GET',
      intervalSeconds: 60,
      connectTimeoutMs: 3000,
      responseTimeoutMs: 10000,
      expectedStatusMin: 200,
      expectedStatusMax: 299,
      enabled: false,
      failureThreshold: 3,
      recoveryThreshold: 2,
      heartbeatGraceSeconds: null,
    })
  })

  it('provides HTTP defaults for a new monitor', () => {
    expect(HTTP_MONITOR_DEFAULTS).toEqual({
      name: '',
      targetUrl: '',
      method: 'GET',
      intervalSeconds: 60,
      connectTimeoutMs: 3000,
      responseTimeoutMs: 10000,
      expectedStatusMin: 200,
      expectedStatusMax: 299,
      failureThreshold: 3,
      recoveryThreshold: 2,
      enabled: true,
    })
  })
})

describe('HTTP monitor validation', () => {
  it('accepts a complete valid form', () => {
    expect(validateHttpMonitorForm(validValues)).toEqual({})
  })

  it('requires a non-blank name of at most 100 characters', () => {
    expect(validateHttpMonitorForm({ ...validValues, name: '   ' }).name).toContain('名称')
    expect(validateHttpMonitorForm({ ...validValues, name: 'x'.repeat(101) }).name).toContain('100')
  })

  it('requires an HTTP or HTTPS URL with a host', () => {
    expect(validateHttpMonitorForm({ ...validValues, targetUrl: '' }).targetUrl).toContain('URL')
    expect(validateHttpMonitorForm({ ...validValues, targetUrl: 'ftp://1.1.1.1/x' }).targetUrl).toContain(
      'http',
    )
    expect(validateHttpMonitorForm({ ...validValues, targetUrl: 'not a url' }).targetUrl).toContain(
      '有效',
    )
  })

  it('rejects user info and fragments in the target URL', () => {
    expect(
      validateHttpMonitorForm({ ...validValues, targetUrl: 'http://user:pass@1.1.1.1/x' })
        .targetUrl,
    ).toContain('用户名')
    expect(
      validateHttpMonitorForm({ ...validValues, targetUrl: 'http://1.1.1.1/x#fragment' }).targetUrl,
    ).toContain('片段')
  })

  it('enforces numeric ranges', () => {
    expect(
      validateHttpMonitorForm({ ...validValues, intervalSeconds: null }).intervalSeconds,
    ).toContain('不能为空')
    expect(
      validateHttpMonitorForm({ ...validValues, intervalSeconds: 9 }).intervalSeconds,
    ).toContain('10')
    expect(
      validateHttpMonitorForm({ ...validValues, connectTimeoutMs: 999 }).connectTimeoutMs,
    ).toContain('1000')
    expect(
      validateHttpMonitorForm({ ...validValues, responseTimeoutMs: 30001 }).responseTimeoutMs,
    ).toContain('30000')
    expect(
      validateHttpMonitorForm({ ...validValues, expectedStatusMin: 99 }).expectedStatusMin,
    ).toContain('100')
    expect(
      validateHttpMonitorForm({ ...validValues, failureThreshold: 11 }).failureThreshold,
    ).toContain('10')
  })

  it('requires an ordered expected status range', () => {
    expect(
      validateHttpMonitorForm({ ...validValues, expectedStatusMin: 300, expectedStatusMax: 200 })
        .expectedStatusMin,
    ).toContain('不能大于')
  })
})
