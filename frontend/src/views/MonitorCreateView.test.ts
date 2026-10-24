import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('MonitorCreateView', () => {
  beforeEach(() => {
    createMonitorMock.mockReset()
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
