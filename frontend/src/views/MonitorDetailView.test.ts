import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getMonitor, listChecks, listIncidents } from '../api/client'
import type { CheckResult, Incident, Monitor, PageResponse } from '../api/types'
import { createAppRouter } from '../router'
import { deferred } from '../test/deferred'
import MonitorDetailView from './MonitorDetailView.vue'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    getMonitor: vi.fn(),
    listChecks: vi.fn(),
    listIncidents: vi.fn(),
  }
})

const getMonitorMock = vi.mocked(getMonitor)
const listChecksMock = vi.mocked(listChecks)
const listIncidentsMock = vi.mocked(listIncidents)

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
    lastHeartbeatAt: '2026-10-24T11:59:30Z',
    lastCheckDurationMs: null,
    ...overrides,
  })
}

function check(id: number, success: boolean, overrides: Partial<CheckResult> = {}): CheckResult {
  return {
    id,
    startedAt: '2026-10-24T11:59:30Z',
    finishedAt: '2026-10-24T11:59:31Z',
    durationMs: 87,
    success,
    failureType: success ? null : 'UNEXPECTED_STATUS',
    httpStatusCode: success ? 200 : 500,
    errorSummary: success ? null : 'HTTP 500',
    ...overrides,
  }
}

function incident(id: number, overrides: Partial<Incident> = {}): Incident {
  return {
    id,
    monitorId: 4,
    monitorName: 'Public API',
    startedAt: '2026-10-24T11:00:00Z',
    resolvedAt: null,
    status: 'OPEN',
    failureType: 'UNEXPECTED_STATUS',
    summary: '连续 3 次探测失败，最新失败：状态码 500',
    ...overrides,
  }
}

function page<T>(items: T[], overrides: Partial<PageResponse<T>> = {}): PageResponse<T> {
  return { items, page: 0, size: 20, totalElements: items.length, ...overrides }
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) {
    throw new Error(`button not found: ${text}`)
  }
  return button
}

async function mountDetail(id = '4') {
  const router = createAppRouter(createMemoryHistory())
  await router.push(`/monitors/${id}`)
  await router.isReady()
  const wrapper = mount(MonitorDetailView, {
    props: { id },
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('MonitorDetailView', () => {
  beforeEach(() => {
    getMonitorMock.mockReset()
    listChecksMock.mockReset()
    listIncidentsMock.mockReset()
  })

  it('renders config, status, history and open incidents', async () => {
    getMonitorMock.mockResolvedValue(monitor())
    listChecksMock.mockResolvedValue(
      page([check(1, true), check(2, false, { durationMs: 120, errorSummary: 'HTTP 500' })]),
    )
    listIncidentsMock.mockResolvedValue(page([incident(11)]))

    const { wrapper } = await mountDetail()

    const text = wrapper.text()
    expect(text).toContain('Public API')
    expect(text).toContain('已启用')
    expect(text).toContain('正常')
    expect(text).toContain('http://8.8.8.8/health')
    expect(text).toContain('UNEXPECTED_STATUS')
    expect(text).toContain('HTTP 500')
    expect(text).toContain('120 ms')
    expect(text).toContain('进行中')
    expect(text).toContain('连续 3 次探测失败')
    expect(wrapper.find('a[href="/incidents?monitor_id=4"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/monitors/4/edit"]').exists()).toBe(true)
    expect(listIncidentsMock).toHaveBeenCalledWith({
      status: 'OPEN',
      monitorId: 4,
      page: 0,
      size: 5,
    })
  })

  it('paginates check history', async () => {
    getMonitorMock.mockResolvedValue(monitor())
    listChecksMock.mockImplementation((_monitorId, pageNumber = 0) =>
      Promise.resolve(page([check(pageNumber + 1, true)], { page: pageNumber, totalElements: 25 })),
    )
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountDetail()
    expect(wrapper.text()).toContain('第 1 / 2 页')

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()

    expect(listChecksMock).toHaveBeenLastCalledWith(4, 1, 20)
    expect(wrapper.text()).toContain('第 2 / 2 页')
  })

  it('shows empty history and incident states', async () => {
    getMonitorMock.mockResolvedValue(monitor())
    listChecksMock.mockResolvedValue(page([]))
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountDetail()

    expect(wrapper.text()).toContain('暂无检查记录')
    expect(wrapper.text()).toContain('当前没有活动 Incident')
  })

  it('shows load errors with code, message and trace id', async () => {
    getMonitorMock.mockRejectedValue(
      new ApiError('monitor 4 was not found', {
        code: 'MONITOR_NOT_FOUND',
        status: 404,
        traceId: 'trace-4',
      }),
    )

    const { wrapper } = await mountDetail()

    expect(wrapper.text()).toContain('MONITOR_NOT_FOUND')
    expect(wrapper.text()).toContain('monitor 4 was not found')
    expect(wrapper.text()).toContain('trace-4')
    expect(listChecksMock).not.toHaveBeenCalled()
    expect(listIncidentsMock).not.toHaveBeenCalled()
    expect(wrapper.find('a[href="/monitors"]').exists()).toBe(true)
  })

  it('separates enabled state from Monitor Status for a disabled monitor', async () => {
    getMonitorMock.mockResolvedValue(monitor({ enabled: false, currentStatus: 'DOWN' }))
    listChecksMock.mockResolvedValue(page([]))
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountDetail()

    const text = wrapper.text()
    expect(text).toContain('已禁用')
    expect(text).toContain('故障')
    expect(text).toContain('DOWN')
    expect(buttonByText(wrapper, '刷新')).toBeDefined()
  })

  it('renders heartbeat config and the token rotation hint', async () => {
    getMonitorMock.mockResolvedValue(heartbeatMonitor())
    listChecksMock.mockResolvedValue(page([]))
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountDetail('8')

    const text = wrapper.text()
    expect(text).toContain('Nightly job')
    expect(text).toContain('预期间隔')
    expect(text).toContain('宽限时间')
    expect(text).toContain('最近心跳')
    expect(text).toContain('令牌不会在详情页显示')
    expect(text).not.toContain('Target URL')
    expect(wrapper.find('a[href="/monitors/8/edit"]').exists()).toBe(true)
  })

  it('shows history errors with a retry action', async () => {
    getMonitorMock.mockResolvedValue(monitor())
    listChecksMock.mockRejectedValue(
      new ApiError('history failed', {
        code: 'INTERNAL_ERROR',
        status: 500,
        traceId: 'trace-5',
      }),
    )
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountDetail()

    expect(wrapper.text()).toContain('INTERNAL_ERROR')
    expect(wrapper.text()).toContain('history failed')
    expect(buttonByText(wrapper, '重试')).toBeDefined()
  })

  it('ignores a late monitor response after the id changes', async () => {
    const monitor4 = deferred<Monitor>()
    const monitor5 = deferred<Monitor>()
    getMonitorMock.mockImplementation((id) => (id === 4 ? monitor4.promise : monitor5.promise))
    listChecksMock.mockResolvedValue(page([]))
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountDetail('4')
    await wrapper.setProps({ id: '5' })

    monitor5.resolve(monitor({ id: 5, name: 'Monitor Five' }))
    await flushPromises()
    expect(wrapper.text()).toContain('Monitor Five')

    monitor4.resolve(monitor({ id: 4, name: 'Monitor Four' }))
    await flushPromises()

    expect(wrapper.text()).toContain('Monitor Five')
    expect(wrapper.text()).not.toContain('Monitor Four')
    expect(listChecksMock).toHaveBeenCalledTimes(1)
    expect(listChecksMock).toHaveBeenCalledWith(5, 0, 20)
  })

  it('ignores a late check error after switching monitors', async () => {
    const checks4 = deferred<PageResponse<CheckResult>>()
    getMonitorMock.mockResolvedValue(monitor())
    listChecksMock.mockImplementation((id) =>
      id === 4
        ? checks4.promise
        : Promise.resolve(page([check(51, false, { errorSummary: 'new check' })])),
    )
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountDetail('4')
    await wrapper.setProps({ id: '5' })
    await flushPromises()
    expect(wrapper.text()).toContain('new check')

    checks4.reject(
      new ApiError('old history failed', {
        code: 'INTERNAL_ERROR',
        status: 500,
        traceId: 'trace-old',
      }),
    )
    await flushPromises()

    expect(wrapper.text()).toContain('new check')
    expect(wrapper.text()).not.toContain('old history failed')
    expect(wrapper.text()).not.toContain('trace-old')
  })

  it('keeps loading while the newest monitor request is still pending', async () => {
    const monitor4 = deferred<Monitor>()
    const monitor5 = deferred<Monitor>()
    getMonitorMock.mockImplementation((id) => (id === 4 ? monitor4.promise : monitor5.promise))
    listChecksMock.mockResolvedValue(page([]))
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountDetail('4')
    await wrapper.setProps({ id: '5' })

    monitor4.resolve(monitor({ id: 4, name: 'Monitor Four' }))
    await flushPromises()

    expect(wrapper.text()).toContain('加载中…')
    expect(wrapper.text()).not.toContain('Monitor Four')

    monitor5.resolve(monitor({ id: 5, name: 'Monitor Five' }))
    await flushPromises()
    expect(wrapper.text()).toContain('Monitor Five')
  })

  it('ignores a late incident error after switching monitors', async () => {
    const incidents4 = deferred<PageResponse<Incident>>()
    getMonitorMock.mockResolvedValue(monitor())
    listChecksMock.mockResolvedValue(page([]))
    listIncidentsMock.mockImplementation((query = {}) =>
      query.monitorId === 4
        ? incidents4.promise
        : Promise.resolve(page([incident(51, { monitorId: 5, summary: 'new incident' })])),
    )

    const { wrapper } = await mountDetail('4')
    await wrapper.setProps({ id: '5' })
    await flushPromises()
    expect(wrapper.text()).toContain('new incident')

    incidents4.reject(
      new ApiError('old incidents failed', {
        code: 'INTERNAL_ERROR',
        status: 500,
        traceId: 'trace-old-incident',
      }),
    )
    await flushPromises()

    expect(wrapper.text()).toContain('new incident')
    expect(wrapper.text()).not.toContain('old incidents failed')
    expect(wrapper.text()).not.toContain('trace-old-incident')
  })
})
