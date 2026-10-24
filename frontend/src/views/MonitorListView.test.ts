import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listMonitors } from '../api/client'
import type { Monitor, PageResponse } from '../api/types'
import MonitorListView from './MonitorListView.vue'

vi.mock('../api/client', () => ({
  listMonitors: vi.fn(),
}))

const listMonitorsMock = vi.mocked(listMonitors)

function monitor(overrides: Partial<Monitor> = {}): Monitor {
  return {
    id: 1,
    name: 'API',
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
    heartbeatGraceSeconds: 60,
    currentStatus: 'UP',
    lastCheckedAt: '2026-10-24T11:59:30Z',
    lastSuccessAt: '2026-10-24T11:59:30Z',
    lastHeartbeatAt: null,
    createdAt: '2026-10-24T00:00:00Z',
    updatedAt: '2026-10-24T00:00:00Z',
    heartbeatToken: null,
    lastCheckDurationMs: 87,
    ...overrides,
  }
}

function page(items: Monitor[], overrides: Partial<PageResponse<Monitor>> = {}): PageResponse<Monitor> {
  return { items, page: 0, size: 20, totalElements: items.length, ...overrides }
}

function mountView() {
  return mount(MonitorListView, {
    global: {
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a :href="to"><slot /></a>',
        },
      },
    },
  })
}

function buttonByText(wrapper: ReturnType<typeof mountView>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) {
    throw new Error(`button not found: ${text}`)
  }
  return button
}

describe('MonitorListView', () => {
  beforeEach(() => {
    listMonitorsMock.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
  })

  it('renders status, latency and disabled state for each monitor', async () => {
    listMonitorsMock.mockResolvedValue(
      page([
        monitor({ id: 1, name: 'API', currentStatus: 'UP', lastCheckDurationMs: 87 }),
        monitor({
          id: 2,
          name: 'Nightly',
          type: 'HEARTBEAT',
          currentStatus: 'UNKNOWN',
          lastCheckDurationMs: null,
          lastCheckedAt: null,
        }),
        monitor({ id: 3, name: 'Paused', enabled: false, currentStatus: 'DOWN' }),
      ]),
    )

    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(3)
    expect(rows[0].text()).toContain('API')
    expect(rows[0].text()).toContain('正常')
    expect(rows[0].text()).toContain('87 ms')
    expect(rows[0].find('[title]').attributes('title')).toMatch(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/,
    )
    expect(rows[1].text()).toContain('Heartbeat')
    expect(rows[1].text()).toContain('—')
    expect(rows[2].text()).toContain('已禁用')
    expect(rows[2].text()).toContain('最后状态：故障')

    wrapper.unmount()
  })

  it('loads the next page using the server page index', async () => {
    listMonitorsMock.mockImplementation((pageNumber = 0) =>
      Promise.resolve(
        page([monitor({ id: pageNumber + 1, name: `Monitor ${pageNumber + 1}` })], {
          page: pageNumber,
          totalElements: 25,
        }),
      ),
    )

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('第 1 / 2 页')
    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()

    expect(listMonitorsMock).toHaveBeenLastCalledWith(1, 20)
    expect(wrapper.text()).toContain('第 2 / 2 页')

    wrapper.unmount()
  })

  it('shows an error and retries without losing the page shell', async () => {
    listMonitorsMock
      .mockRejectedValueOnce(new Error('网络请求失败'))
      .mockResolvedValueOnce(page([]))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('网络请求失败')
    await buttonByText(wrapper, '重试').trigger('click')
    await flushPromises()

    expect(listMonitorsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('暂无监控项')

    wrapper.unmount()
  })

  it('refreshes manually', async () => {
    listMonitorsMock.mockResolvedValue(page([monitor()]))

    const wrapper = mountView()
    await flushPromises()
    await buttonByText(wrapper, '刷新').trigger('click')
    await flushPromises()

    expect(listMonitorsMock).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('falls back to the last valid page when the current page becomes empty', async () => {
    listMonitorsMock
      .mockResolvedValueOnce(
        page([monitor({ id: 1 }), monitor({ id: 2 })], { page: 0, totalElements: 40 }),
      )
      .mockResolvedValueOnce(page([], { page: 1, totalElements: 20 }))
      .mockResolvedValueOnce(page([monitor({ id: 1 })], { page: 0, totalElements: 20 }))

    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('第 1 / 2 页')

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()

    expect(listMonitorsMock).toHaveBeenLastCalledWith(0, 20)
    expect(wrapper.text()).toContain('第 1 / 1 页')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)

    wrapper.unmount()
  })

  it('polls every thirty seconds only while the page is visible', async () => {
    vi.useFakeTimers()
    listMonitorsMock.mockResolvedValue(page([monitor()]))

    const wrapper = mountView()
    await vi.advanceTimersByTimeAsync(0)
    expect(listMonitorsMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(listMonitorsMock).toHaveBeenCalledTimes(2)

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    await vi.advanceTimersByTimeAsync(30_000)
    expect(listMonitorsMock).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })
})
