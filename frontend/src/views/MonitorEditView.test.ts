import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  getMonitor,
  revokeHeartbeatToken,
  rotateHeartbeatToken,
  updateMonitor,
} from '../api/client'
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
    revokeHeartbeatToken: vi.fn(),
    rotateHeartbeatToken: vi.fn(),
    updateMonitor: vi.fn(),
  }
})

const getMonitorMock = vi.mocked(getMonitor)
const updateMonitorMock = vi.mocked(updateMonitor)
const rotateHeartbeatTokenMock = vi.mocked(rotateHeartbeatToken)
const revokeHeartbeatTokenMock = vi.mocked(revokeHeartbeatToken)

function heartbeatMonitor(overrides: Partial<Monitor> = {}): Monitor {
  return monitor({
    name: 'Nightly job',
    type: 'HEARTBEAT',
    targetUrl: null,
    method: null,
    intervalSeconds: 300,
    connectTimeoutMs: null,
    responseTimeoutMs: null,
    expectedStatusMin: null,
    expectedStatusMax: null,
    failureThreshold: null,
    recoveryThreshold: null,
    heartbeatGraceSeconds: 60,
    ...overrides,
  })
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) {
    throw new Error(`button not found: ${text}`)
  }
  return button
}

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
    rotateHeartbeatTokenMock.mockReset()
    revokeHeartbeatTokenMock.mockReset()
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

  it('reloads when the route id changes', async () => {
    getMonitorMock
      .mockResolvedValueOnce(monitor({ id: 4, name: 'First' }))
      .mockResolvedValueOnce(monitor({ id: 5, name: 'Second' }))
    const { wrapper } = await mountEdit('4')

    expect((wrapper.find('#monitor-name').element as HTMLInputElement).value).toBe('First')

    await wrapper.setProps({ id: '5' })
    await flushPromises()

    expect(getMonitorMock).toHaveBeenLastCalledWith(5)
    expect((wrapper.find('#monitor-name').element as HTMLInputElement).value).toBe('Second')
  })

  it('edits a heartbeat monitor and rotates or revokes its token', async () => {
    getMonitorMock.mockResolvedValue(heartbeatMonitor())
    updateMonitorMock.mockResolvedValue(heartbeatMonitor({ intervalSeconds: 600 }))
    rotateHeartbeatTokenMock.mockResolvedValue({ heartbeatToken: 'rotated-9' })
    revokeHeartbeatTokenMock.mockResolvedValue(undefined)
    const { wrapper, router } = await mountEdit('8')

    expect((wrapper.find('#heartbeat-name').element as HTMLInputElement).value).toBe(
      'Nightly job',
    )
    expect((wrapper.find('#heartbeat-interval').element as HTMLInputElement).value).toBe('300')

    await wrapper.find('#heartbeat-interval').setValue(600)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(updateMonitorMock).toHaveBeenCalledWith(8, {
      name: 'Nightly job',
      type: 'HEARTBEAT',
      intervalSeconds: 600,
      heartbeatGraceSeconds: 60,
      enabled: true,
    })
    expect(wrapper.text()).toContain('已保存')

    await buttonByText(wrapper, '轮换令牌').trigger('click')
    await flushPromises()

    expect(rotateHeartbeatTokenMock).toHaveBeenCalledWith(8)
    expect((wrapper.find('#heartbeat-token').element as HTMLInputElement).value).toBe(
      'rotated-9',
    )
    expect(router.currentRoute.value.fullPath).toBe('/monitors/8/edit')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)

    await buttonByText(wrapper, '撤销令牌').trigger('click')
    await flushPromises()

    expect(revokeHeartbeatTokenMock).toHaveBeenCalledWith(8)
    expect(wrapper.find('#heartbeat-token').exists()).toBe(false)
    expect(wrapper.text()).toContain('令牌已撤销')
  })

  it('refills the heartbeat form with server values after saving', async () => {
    getMonitorMock.mockResolvedValue(heartbeatMonitor())
    updateMonitorMock.mockResolvedValue(
      heartbeatMonitor({ name: 'Trimmed', intervalSeconds: 600 }),
    )
    const { wrapper } = await mountEdit('8')

    await wrapper.find('#heartbeat-name').setValue('  Trimmed  ')
    await wrapper.find('#heartbeat-interval').setValue(600)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect((wrapper.find('#heartbeat-name').element as HTMLInputElement).value).toBe('Trimmed')
  })

  it('keeps token actions available for a disabled heartbeat monitor', async () => {
    getMonitorMock.mockResolvedValue(heartbeatMonitor({ enabled: false }))
    const { wrapper } = await mountEdit('8')

    expect(buttonByText(wrapper, '轮换令牌').attributes('disabled')).toBeUndefined()
    expect(buttonByText(wrapper, '撤销令牌').attributes('disabled')).toBeUndefined()
  })

  it('does not show a token on re-entry and prompts rotation', async () => {
    getMonitorMock.mockResolvedValue(heartbeatMonitor())
    const { wrapper } = await mountEdit('8')

    expect(wrapper.find('#heartbeat-token').exists()).toBe(false)
    expect(wrapper.text()).toContain('遗失后必须轮换')
  })

  it('shows token errors with code, message and trace id', async () => {
    getMonitorMock.mockResolvedValue(heartbeatMonitor())
    rotateHeartbeatTokenMock.mockRejectedValue(
      new ApiError('rotation failed', {
        code: 'INTERNAL_ERROR',
        status: 500,
        traceId: 'trace-8',
      }),
    )
    const { wrapper } = await mountEdit('8')

    await buttonByText(wrapper, '轮换令牌').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('INTERNAL_ERROR')
    expect(wrapper.text()).toContain('rotation failed')
    expect(wrapper.text()).toContain('trace-8')
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
