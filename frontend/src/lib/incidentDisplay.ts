import type { IncidentStatus, NotificationDeliveryStatus } from '../api/types'

const INCIDENT_STATUS_LABELS: Record<IncidentStatus, string> = {
  OPEN: '进行中',
  RESOLVED: '已恢复',
}

const INCIDENT_STATUS_CLASSES: Record<IncidentStatus, string> = {
  OPEN: 'status-down',
  RESOLVED: 'status-up',
}

const DELIVERY_STATUS_LABELS: Record<NotificationDeliveryStatus, string> = {
  PENDING: '待发送',
  SENT: '已发送',
  FAILED: '失败',
}

const DELIVERY_STATUS_CLASSES: Record<NotificationDeliveryStatus, string> = {
  PENDING: 'status-unknown',
  SENT: 'status-up',
  FAILED: 'status-down',
}

export function incidentStatusLabel(status: IncidentStatus): string {
  return INCIDENT_STATUS_LABELS[status]
}

export function incidentStatusClass(status: IncidentStatus): string {
  return INCIDENT_STATUS_CLASSES[status]
}

export function deliveryStatusLabel(status: NotificationDeliveryStatus): string {
  return DELIVERY_STATUS_LABELS[status]
}

export function deliveryStatusClass(status: NotificationDeliveryStatus): string {
  return DELIVERY_STATUS_CLASSES[status]
}

const CHANNEL_LABELS: Record<string, string> = {
  TELEGRAM: 'Telegram',
}

const EVENT_LABELS: Record<string, string> = {
  INCIDENT_OPENED: '故障开始',
  INCIDENT_RESOLVED: '故障恢复',
}

export function deliveryChannelLabel(channel: string): string {
  return CHANNEL_LABELS[channel] ?? channel
}

export function deliveryEventLabel(eventType: string): string {
  return EVENT_LABELS[eventType] ?? eventType
}

export function displayOrDash(value: string | number | null | undefined): string {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}
