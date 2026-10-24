import type { MonitorStatus, MonitorType } from '../api/types'

const STATUS_LABELS: Record<MonitorStatus, string> = {
  UP: '正常',
  DOWN: '故障',
  UNKNOWN: '未知',
}

const STATUS_CLASSES: Record<MonitorStatus, string> = {
  UP: 'status-up',
  DOWN: 'status-down',
  UNKNOWN: 'status-unknown',
}

const TYPE_LABELS: Record<MonitorType, string> = {
  HTTP: 'HTTP',
  HEARTBEAT: 'Heartbeat',
}

export function lastKnownStatusLabel(status: MonitorStatus): string {
  return STATUS_LABELS[status]
}

export function statusLabel(status: MonitorStatus, enabled: boolean): string {
  return enabled ? STATUS_LABELS[status] : '已禁用'
}

export function statusClass(status: MonitorStatus, enabled: boolean): string {
  return enabled ? STATUS_CLASSES[status] : 'status-disabled'
}

export function typeLabel(type: MonitorType): string {
  return TYPE_LABELS[type]
}

export function checkResultLabel(success: boolean): string {
  return success ? '成功' : '失败'
}

export function checkResultClass(success: boolean): string {
  return success ? 'status-up' : 'status-down'
}
