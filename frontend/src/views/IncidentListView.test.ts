import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getIncident, listIncidents } from '../api/client'
import type {
  Incident,
  IncidentDetail,
  NotificationDelivery,
  PageResponse,
} from '../api/types'
import { createAppRouter } from '../router'
import { authenticateForTest } from '../test/auth'
import { deferred } from '../test/deferred'
import IncidentListView from './IncidentListView.vue'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    getIncident: vi.fn(),
    listIncidents: vi.fn(),
  }
})

const listIncidentsMock = vi.mocked(listIncidents)
const getIncidentMock = vi.mocked(getIncident)

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

function delivery(id: number, overrides: Partial<NotificationDelivery> = {}): NotificationDelivery {
  return {
    id,
    channel: 'TELEGRAM',
    eventType: 'INCIDENT_OPENED',
    status: 'PENDING',
    attemptCount: 1,
    lastAttemptAt: '2026-10-24T11:01:00Z',
    sentAt: null,
    lastErrorSummary: null,
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

async function mountIncidents(path = '/incidents') {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  const wrapper = mount(IncidentListView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('IncidentListView', () => {
  beforeEach(() => {
    authenticateForTest()
    listIncidentsMock.mockReset()
    getIncidentMock.mockReset()
  })

  it('defaults to open incidents and applies the monitor filter from the URL', async () => {
    listIncidentsMock.mockResolvedValue(page([incident(11)]))

    const { wrapper } = await mountIncidents('/incidents?monitor_id=4')

    expect(listIncidentsMock).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'OPEN', monitorId: 4, page: 0, size: 20 }),
    )
    expect(wrapper.text()).toContain('仅显示监控项 #4')
    expect(wrapper.text()).toContain('连续 3 次探测失败')
    expect(wrapper.find('a[href="/incidents"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/monitors/4"]').exists()).toBe(true)
  })

  it('switches between open and resolved incidents', async () => {
    listIncidentsMock.mockResolvedValue(page([incident(11, { status: 'RESOLVED' })]))

    const { wrapper } = await mountIncidents()
    await wrapper.find('#incident-status').setValue('RESOLVED')
    await flushPromises()

    expect(listIncidentsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'RESOLVED', page: 0, size: 20 }),
    )
  })

  it('expands an incident into its recent deliveries', async () => {
    listIncidentsMock.mockResolvedValue(page([incident(11)]))
    getIncidentMock.mockResolvedValue({
      ...incident(11),
      deliveries: [
        delivery(1, { status: 'PENDING', lastErrorSummary: 'HTTP 500 from Telegram' }),
        delivery(2, {
          status: 'SENT',
          attemptCount: 2,
          sentAt: '2026-10-24T11:05:00Z',
          lastErrorSummary: null,
        }),
      ],
    } as IncidentDetail)

    const { wrapper } = await mountIncidents()
    await buttonByText(wrapper, '展开').trigger('click')
    await flushPromises()

    expect(getIncidentMock).toHaveBeenCalledWith(11)
    const text = wrapper.text()
    expect(text).toContain('待发送')
    expect(text).toContain('已发送')
    expect(text).toContain('HTTP 500 from Telegram')
    expect(text).toContain('尝试次数')
    expect(text).toContain('Telegram')
    expect(text).toContain('故障开始')

    await buttonByText(wrapper, '收起').trigger('click')
    expect(wrapper.text()).not.toContain('HTTP 500 from Telegram')
  })

  it('shows an empty state', async () => {
    listIncidentsMock.mockResolvedValue(page([]))

    const { wrapper } = await mountIncidents()

    expect(wrapper.text()).toContain('暂无 Incident')
  })

  it('shows load errors with code, message and trace id', async () => {
    listIncidentsMock.mockRejectedValue(
      new ApiError('incidents failed', {
        code: 'INTERNAL_ERROR',
        status: 500,
        traceId: 'trace-9',
      }),
    )

    const { wrapper } = await mountIncidents()

    expect(wrapper.text()).toContain('INTERNAL_ERROR')
    expect(wrapper.text()).toContain('incidents failed')
    expect(wrapper.text()).toContain('trace-9')
    expect(buttonByText(wrapper, '重试')).toBeDefined()
  })

  it('clears stale rows when a filter change fails', async () => {
    listIncidentsMock
      .mockResolvedValueOnce(page([incident(11)]))
      .mockRejectedValueOnce(
        new ApiError('resolved failed', {
          code: 'INTERNAL_ERROR',
          status: 500,
          traceId: 'trace-10',
        }),
      )

    const { wrapper } = await mountIncidents()
    expect(wrapper.text()).toContain('连续 3 次探测失败')

    await wrapper.find('#incident-status').setValue('RESOLVED')
    await flushPromises()

    expect(wrapper.text()).toContain('resolved failed')
    expect(wrapper.text()).not.toContain('连续 3 次探测失败')
  })

  it('paginates incidents', async () => {
    listIncidentsMock.mockImplementation((query = {}) =>
      Promise.resolve(
        page([incident((query.page ?? 0) + 1)], {
          page: query.page ?? 0,
          totalElements: 25,
        }),
      ),
    )

    const { wrapper } = await mountIncidents()
    expect(wrapper.text()).toContain('第 1 / 2 页')

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()

    expect(listIncidentsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, size: 20 }),
    )
    expect(wrapper.text()).toContain('第 2 / 2 页')
  })

  it('reloads when the monitor filter changes in the URL', async () => {
    listIncidentsMock.mockResolvedValue(page([incident(11)]))

    const { router } = await mountIncidents()
    await router.push('/incidents?monitor_id=5')
    await flushPromises()

    expect(listIncidentsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ monitorId: 5, page: 0 }),
    )
  })

  it('ignores a late response after changing the status filter', async () => {
    const open = deferred<PageResponse<Incident>>()
    const resolved = deferred<PageResponse<Incident>>()
    listIncidentsMock.mockImplementation((query = {}) =>
      query.status === 'OPEN' ? open.promise : resolved.promise,
    )

    const { wrapper } = await mountIncidents()
    await wrapper.find('#incident-status').setValue('RESOLVED')

    resolved.resolve(page([incident(21, { status: 'RESOLVED', summary: 'resolved row' })]))
    await flushPromises()
    expect(wrapper.text()).toContain('resolved row')

    open.resolve(page([incident(11, { status: 'OPEN', summary: 'open row' })]))
    await flushPromises()

    expect(wrapper.text()).toContain('resolved row')
    expect(wrapper.text()).not.toContain('open row')
  })

  it('ignores a late error after changing the status filter', async () => {
    const open = deferred<PageResponse<Incident>>()
    listIncidentsMock.mockImplementation((query = {}) =>
      query.status === 'OPEN'
        ? open.promise
        : Promise.resolve(page([incident(21, { status: 'RESOLVED', summary: 'resolved row' })])),
    )

    const { wrapper } = await mountIncidents()
    await wrapper.find('#incident-status').setValue('RESOLVED')
    await flushPromises()
    expect(wrapper.text()).toContain('resolved row')

    open.reject(
      new ApiError('old incidents failed', {
        code: 'INTERNAL_ERROR',
        status: 500,
        traceId: 'trace-old',
      }),
    )
    await flushPromises()

    expect(wrapper.text()).toContain('resolved row')
    expect(wrapper.text()).not.toContain('old incidents failed')
    expect(wrapper.text()).not.toContain('trace-old')
  })

  it('keeps loading while the newest filter request is still pending', async () => {
    const open = deferred<PageResponse<Incident>>()
    const resolved = deferred<PageResponse<Incident>>()
    listIncidentsMock.mockImplementation((query = {}) =>
      query.status === 'OPEN' ? open.promise : resolved.promise,
    )

    const { wrapper } = await mountIncidents()
    await wrapper.find('#incident-status').setValue('RESOLVED')

    open.resolve(page([incident(11, { status: 'OPEN', summary: 'open row' })]))
    await flushPromises()

    expect(wrapper.text()).toContain('加载中…')
    expect(wrapper.text()).not.toContain('open row')

    resolved.resolve(page([incident(21, { status: 'RESOLVED', summary: 'resolved row' })]))
    await flushPromises()
    expect(wrapper.text()).toContain('resolved row')
  })
})
