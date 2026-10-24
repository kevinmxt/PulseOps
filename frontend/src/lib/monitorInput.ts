import type { HttpMethod, Monitor, MonitorInput, MonitorType } from '../api/types'

export interface HttpMonitorFormValues {
  name: string
  targetUrl: string
  method: HttpMethod
  intervalSeconds: number | null
  connectTimeoutMs: number | null
  responseTimeoutMs: number | null
  expectedStatusMin: number | null
  expectedStatusMax: number | null
  failureThreshold: number | null
  recoveryThreshold: number | null
  enabled: boolean
}

export type MonitorFormErrors = Partial<Record<keyof HttpMonitorFormValues, string>>

export const HTTP_MONITOR_DEFAULTS: HttpMonitorFormValues = {
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
}

export function monitorToFormValues(monitor: Monitor): HttpMonitorFormValues {
  return {
    name: monitor.name,
    targetUrl: monitor.targetUrl ?? '',
    method: monitor.method ?? 'GET',
    intervalSeconds: monitor.intervalSeconds,
    connectTimeoutMs: monitor.connectTimeoutMs,
    responseTimeoutMs: monitor.responseTimeoutMs,
    expectedStatusMin: monitor.expectedStatusMin,
    expectedStatusMax: monitor.expectedStatusMax,
    failureThreshold: monitor.failureThreshold,
    recoveryThreshold: monitor.recoveryThreshold,
    enabled: monitor.enabled,
  }
}

export function formValuesToMonitorInput(
  values: HttpMonitorFormValues,
  type: MonitorType = 'HTTP',
): MonitorInput {
  return {
    name: values.name.trim(),
    type,
    targetUrl: values.targetUrl.trim(),
    method: values.method,
    intervalSeconds: values.intervalSeconds,
    connectTimeoutMs: values.connectTimeoutMs,
    responseTimeoutMs: values.responseTimeoutMs,
    expectedStatusMin: values.expectedStatusMin,
    expectedStatusMax: values.expectedStatusMax,
    enabled: values.enabled,
    failureThreshold: values.failureThreshold,
    recoveryThreshold: values.recoveryThreshold,
  }
}

export function monitorToInput(monitor: Monitor, enabled = monitor.enabled): MonitorInput {
  return {
    name: monitor.name,
    type: monitor.type,
    targetUrl: monitor.targetUrl,
    method: monitor.method,
    intervalSeconds: monitor.intervalSeconds,
    connectTimeoutMs: monitor.connectTimeoutMs,
    responseTimeoutMs: monitor.responseTimeoutMs,
    expectedStatusMin: monitor.expectedStatusMin,
    expectedStatusMax: monitor.expectedStatusMax,
    enabled,
    failureThreshold: monitor.failureThreshold,
    recoveryThreshold: monitor.recoveryThreshold,
    heartbeatGraceSeconds: monitor.heartbeatGraceSeconds,
  }
}

export function validateHttpMonitorForm(values: HttpMonitorFormValues): MonitorFormErrors {
  const errors: MonitorFormErrors = {}
  const name = values.name.trim()
  if (!name) {
    errors.name = '名称不能为空'
  } else if (name.length > 100) {
    errors.name = '名称最多 100 个字符'
  }

  const urlError = validateTargetUrl(values.targetUrl)
  if (urlError) {
    errors.targetUrl = urlError
  }

  requireRange(errors, 'intervalSeconds', values.intervalSeconds, 10, 86400, '检查间隔')
  requireRange(errors, 'connectTimeoutMs', values.connectTimeoutMs, 1000, 10000, '连接超时')
  requireRange(errors, 'responseTimeoutMs', values.responseTimeoutMs, 1000, 30000, '响应超时')
  requireRange(errors, 'expectedStatusMin', values.expectedStatusMin, 100, 599, '最小状态码')
  requireRange(errors, 'expectedStatusMax', values.expectedStatusMax, 100, 599, '最大状态码')
  requireRange(errors, 'failureThreshold', values.failureThreshold, 1, 10, '失败阈值')
  requireRange(errors, 'recoveryThreshold', values.recoveryThreshold, 1, 10, '恢复阈值')

  if (
    values.expectedStatusMin !== null &&
    values.expectedStatusMax !== null &&
    values.expectedStatusMin > values.expectedStatusMax &&
    !errors.expectedStatusMin
  ) {
    errors.expectedStatusMin = '最小状态码不能大于最大状态码'
  }

  return errors
}

function validateTargetUrl(raw: string): string | null {
  const value = raw.trim()
  if (!value) {
    return 'URL 不能为空'
  }
  if (value.length > 2048) {
    return 'URL 最多 2048 个字符'
  }

  let url: URL
  try {
    url = new URL(value)
  } catch {
    return '请输入有效的 URL'
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return 'URL 必须使用 http 或 https'
  }
  if (!url.hostname) {
    return 'URL 必须包含主机名'
  }
  if (url.username || url.password) {
    return 'URL 不能包含用户名或密码'
  }
  if (url.hash) {
    return 'URL 不能包含片段'
  }
  if (url.port === '0' || Number(url.port) > 65535) {
    return 'URL 端口无效'
  }
  return null
}

function requireRange(
  errors: MonitorFormErrors,
  field: keyof HttpMonitorFormValues,
  value: number | null,
  min: number,
  max: number,
  label: string,
): void {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    errors[field] = `${label}不能为空`
    return
  }
  if (!Number.isInteger(value) || value < min || value > max) {
    errors[field] = `${label}必须在 ${min}–${max} 之间`
  }
}
