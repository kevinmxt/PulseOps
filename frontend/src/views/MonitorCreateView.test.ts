import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createMonitor } from '../api/client'
import type { Monitor } from '../api/types'
import { createAppRouter } from '../router'
import MonitorCreateView from './MonitorCreateView.vue'

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

const createMonitorMock = vi.mocked(createMonitor)

async function mountCreate() {
  const router = createAppRouter(createMemoryHistory())
  await router.push('/monitors/new')
  await router.isReady()
  const wrapper = mount(MonitorCreateView, { global: { plugins: [router] } })
  return { wrapper, router }
}

function createdMonitor(id: number): Monitor {
  return { id } as Monitor
}

function createdHeartbeat(id: number, token: string): Monitor {
  return { id, type: 'HEARTBEAT', heartbeatToken: token } as Monitor
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) {
    throw new Error(`button not found: ${text}`)
  }
  return button
}

describe('MonitorCreateView', () => {
  beforeEach(() => {
    createMonitorMock.mockReset()
  })

  afterEach(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
  })

  it('creates an HTTP monitor and navigates to its detail page', async () => {
    createMonitorMock.mockResolvedValue(createdMonitor(9))
    const { wrapper, router } = await mountCreate()

    await wrapper.find('#monitor-name').setValue('New API')
    await wrapper.find('#monitor-url').setValue('https://1.1.1.1/health')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createMonitorMock).toHaveBeenCalledWith({
      name: 'New API',
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
    expect(router.currentRoute.value.fullPath).toBe('/monitors/9')
  })

  it('blocks submission when client validation fails', async () => {
    const { wrapper } = await mountCreate()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createMonitorMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('名称不能为空')
    expect(wrapper.text()).toContain('URL 不能为空')
  })

  it('creates a heartbeat monitor and shows its one-time token and command', async () => {
    createMonitorMock.mockResolvedValue(createdHeartbeat(9, 'tok-9'))
    const { wrapper, router } = await mountCreate()

    await wrapper.find('input[value="HEARTBEAT"]').setValue(true)
    await wrapper.find('#heartbeat-name').setValue('Nightly job')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createMonitorMock).toHaveBeenCalledWith({
      name: 'Nightly job',
      type: 'HEARTBEAT',
      intervalSeconds: 300,
      heartbeatGraceSeconds: 60,
      enabled: true,
    })
    expect(router.currentRoute.value.fullPath).toBe('/monitors/new')
    expect((wrapper.find('#heartbeat-token').element as HTMLInputElement).value).toBe('tok-9')
    const command = (wrapper.find('#heartbeat-command').element as HTMLInputElement).value
    expect(command).toContain(window.location.origin)
    expect(command).toContain('/api/v1/heartbeats/tok-9')
    expect(wrapper.text()).toContain('只显示一次')
    expect(wrapper.find('a[href="/monitors/9"]').exists()).toBe(true)
  })

  it('hides the token after the user confirms it is saved', async () => {
    createMonitorMock.mockResolvedValue(createdHeartbeat(9, 'tok-9'))
    const { wrapper } = await mountCreate()

    await wrapper.find('input[value="HEARTBEAT"]').setValue(true)
    await wrapper.find('#heartbeat-name').setValue('Nightly job')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    await buttonByText(wrapper, '我已保存').trigger('click')

    expect(wrapper.find('#heartbeat-token').exists()).toBe(false)
    expect(wrapper.text()).toContain('令牌已保存')
  })

  it('copies the token and the command', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    createMonitorMock.mockResolvedValue(createdHeartbeat(9, 'tok-9'))
    const { wrapper } = await mountCreate()

    await wrapper.find('input[value="HEARTBEAT"]').setValue(true)
    await wrapper.find('#heartbeat-name').setValue('Nightly job')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    await buttonByText(wrapper, '复制令牌').trigger('click')
    expect(writeText).toHaveBeenCalledWith('tok-9')

    await buttonByText(wrapper, '复制命令').trigger('click')
    expect(writeText).toHaveBeenLastCalledWith(
      expect.stringContaining('/api/v1/heartbeats/tok-9'),
    )
  })

  it('falls back to manual selection when the clipboard is unavailable', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    createMonitorMock.mockResolvedValue(createdHeartbeat(9, 'tok-9'))
    const { wrapper } = await mountCreate()

    await wrapper.find('input[value="HEARTBEAT"]').setValue(true)
    await wrapper.find('#heartbeat-name').setValue('Nightly job')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    await buttonByText(wrapper, '复制令牌').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('复制失败，请手动选择文本。')
    expect(wrapper.find('#heartbeat-token').attributes('readonly')).toBeDefined()
  })

  it('validates the heartbeat interval before submitting', async () => {
    const { wrapper } = await mountCreate()

    await wrapper.find('input[value="HEARTBEAT"]').setValue(true)
    await wrapper.find('#heartbeat-name').setValue('Nightly job')
    await wrapper.find('#heartbeat-interval').setValue(29)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createMonitorMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('30')
  })

  it('shows the server error code, message and trace id', async () => {
    createMonitorMock.mockRejectedValue(
      new ApiError('name must not be blank', {
        code: 'VALIDATION_ERROR',
        status: 400,
        traceId: 'trace-9',
      }),
    )
    const { wrapper } = await mountCreate()

    await wrapper.find('#monitor-name').setValue('New API')
    await wrapper.find('#monitor-url').setValue('https://1.1.1.1/health')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('VALIDATION_ERROR')
    expect(wrapper.text()).toContain('name must not be blank')
    expect(wrapper.text()).toContain('trace-9')
  })
})
