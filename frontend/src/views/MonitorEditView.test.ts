import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getMonitor, updateMonitor } from '../api/client'
import type { Monitor } from '../api/types'
import { createAppRouter } from '../router'
import MonitorEditView from './MonitorEditView.vue'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    createMonitor: vi.fn(),
    deleteMonitor: vi.fn(),
    getMonitor: vi.fn(),
    listMonitors: vi.fn(),
    updateMonitor: vi.fn(),
  }
})

const getMonitorMock = vi.mocked(getMonitor)
const updateMonitorMock = vi.mocked(updateMonitor)

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

async function mountEdit(id = '4') {
  const router = createAppRouter(createMemoryHistory())
  await router.push(`/monitors/${id}/edit`)
  await router.isReady()
  const wrapper = mount(MonitorEditView, {
    props: { id },
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('MonitorEditView', () => {
  beforeEach(() => {
    getMonitorMock.mockReset()
    updateMonitorMock.mockReset()
  })

  it('loads the current config, saves the full payload and navigates to detail', async () => {
    getMonitorMock.mockResolvedValue(monitor())
    updateMonitorMock.mockResolvedValue(monitor({ name: 'Updated API' }))
    const { wrapper, router } = await mountEdit()

    const nameInput = wrapper.find('#monitor-name')
    expect((nameInput.element as HTMLInputElement).value).toBe('Public API')

    await nameInput.setValue('Updated API')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(updateMonitorMock).toHaveBeenCalledWith(4, {
      name: 'Updated API',
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
    })
    expect(router.currentRoute.value.fullPath).toBe('/monitors/4')
  })

  it('shows a notice instead of the form for heartbeat monitors', async () => {
    getMonitorMock.mockResolvedValue(
      monitor({
        type: 'HEARTBEAT',
        targetUrl: null,
        method: null,
        failureThreshold: null,
        recoveryThreshold: null,
        heartbeatGraceSeconds: 60,
      }),
    )
    const { wrapper } = await mountEdit()

    expect(wrapper.text()).toContain('心跳监控项')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('shows load errors with code, message and trace id', async () => {
    getMonitorMock.mockRejectedValue(
      new ApiError('monitor 4 was not found', {
        code: 'MONITOR_NOT_FOUND',
        status: 404,
        traceId: 'trace-4',
      }),
    )
    const { wrapper } = await mountEdit()

    expect(wrapper.text()).toContain('MONITOR_NOT_FOUND')
    expect(wrapper.text()).toContain('monitor 4 was not found')
    expect(wrapper.text()).toContain('trace-4')
  })

  it('shows save errors with code, message and trace id', async () => {
    getMonitorMock.mockResolvedValue(monitor())
    updateMonitorMock.mockRejectedValue(
      new ApiError('name must not be blank', {
        code: 'VALIDATION_ERROR',
        status: 400,
        traceId: 'trace-5',
      }),
    )
    const { wrapper } = await mountEdit()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('VALIDATION_ERROR')
    expect(wrapper.text()).toContain('name must not be blank')
    expect(wrapper.text()).toContain('trace-5')
  })
})
