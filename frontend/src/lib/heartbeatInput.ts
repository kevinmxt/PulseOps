import type { Monitor, MonitorInput } from '../api/types'
import { requireIntegerRange, validateMonitorName } from './formValidation'

export interface HeartbeatFormValues {
  name: string
  intervalSeconds: number | null
  heartbeatGraceSeconds: number | null
  enabled: boolean
}

export type HeartbeatFormErrors = Partial<Record<keyof HeartbeatFormValues, string>>

export const HEARTBEAT_INTERVAL_MIN_SECONDS = 30
export const HEARTBEAT_INTERVAL_MAX_SECONDS = 2592000
export const HEARTBEAT_GRACE_MIN_SECONDS = 0
export const HEARTBEAT_GRACE_MAX_SECONDS = 86400

export const HEARTBEAT_MONITOR_DEFAULTS: HeartbeatFormValues = {
  name: '',
  intervalSeconds: 300,
  heartbeatGraceSeconds: 60,
  enabled: true,
}

export function monitorToHeartbeatFormValues(monitor: Monitor): HeartbeatFormValues {
  return {
    name: monitor.name,
    intervalSeconds: monitor.intervalSeconds,
    heartbeatGraceSeconds: monitor.heartbeatGraceSeconds ?? 60,
    enabled: monitor.enabled,
  }
}

export function heartbeatFormToMonitorInput(values: HeartbeatFormValues): MonitorInput {
  return {
    name: values.name.trim(),
    type: 'HEARTBEAT',
    intervalSeconds: values.intervalSeconds,
    heartbeatGraceSeconds: values.heartbeatGraceSeconds,
    enabled: values.enabled,
  }
}

export function validateHeartbeatForm(values: HeartbeatFormValues): HeartbeatFormErrors {
  const errors: HeartbeatFormErrors = {}
  const nameError = validateMonitorName(values.name)
  if (nameError) {
    errors.name = nameError
  }
  requireIntegerRange(
    errors,
    'intervalSeconds',
    values.intervalSeconds,
    HEARTBEAT_INTERVAL_MIN_SECONDS,
    HEARTBEAT_INTERVAL_MAX_SECONDS,
    '预期间隔',
    '秒',
  )
  requireIntegerRange(
    errors,
    'heartbeatGraceSeconds',
    values.heartbeatGraceSeconds,
    HEARTBEAT_GRACE_MIN_SECONDS,
    HEARTBEAT_GRACE_MAX_SECONDS,
    '宽限时间',
    '秒',
  )
  return errors
}
