import { describe, expect, it } from 'vitest'
import {
  deliveryChannelLabel,
  deliveryEventLabel,
  deliveryStatusClass,
  deliveryStatusLabel,
  displayOrDash,
  incidentStatusClass,
  incidentStatusLabel,
} from './incidentDisplay'

describe('incident display labels', () => {
  it('labels incident statuses', () => {
    expect(incidentStatusLabel('OPEN')).toBe('进行中')
    expect(incidentStatusLabel('RESOLVED')).toBe('已恢复')
    expect(incidentStatusClass('OPEN')).toBe('status-down')
    expect(incidentStatusClass('RESOLVED')).toBe('status-up')
  })

  it('labels notification delivery statuses', () => {
    expect(deliveryStatusLabel('PENDING')).toBe('待发送')
    expect(deliveryStatusLabel('SENT')).toBe('已发送')
    expect(deliveryStatusLabel('FAILED')).toBe('失败')
    expect(deliveryStatusClass('PENDING')).toBe('status-unknown')
    expect(deliveryStatusClass('SENT')).toBe('status-up')
    expect(deliveryStatusClass('FAILED')).toBe('status-down')
  })

  it('labels notification channels and events', () => {
    expect(deliveryChannelLabel('TELEGRAM')).toBe('Telegram')
    expect(deliveryEventLabel('INCIDENT_OPENED')).toBe('故障开始')
    expect(deliveryEventLabel('INCIDENT_RESOLVED')).toBe('故障恢复')
    expect(deliveryEventLabel('UNKNOWN_EVENT')).toBe('UNKNOWN_EVENT')
  })

  it('renders a dash for absent values', () => {
    expect(displayOrDash(null)).toBe('—')
    expect(displayOrDash(undefined)).toBe('—')
    expect(displayOrDash('')).toBe('—')
    expect(displayOrDash(204)).toBe('204')
  })
})
