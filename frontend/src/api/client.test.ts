import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  createMonitor,
  deleteMonitor,
  getIncident,
  getMonitor,
  listChecks,
  listIncidents,
  listMonitors,
  revokeHeartbeatToken,
  rotateHeartbeatToken,
  updateMonitor,
} from './client'

describe('api client', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads a page of monitors with pagination parameters', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({
        items: [{ id: 1, name: 'Public API' }],
        page: 0,
        size: 20,
        totalElements: 1,
      }),
    )

    const result = await listMonitors(0, 20)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors?page=0&size=20',
      expect.objectContaining({ method: 'GET', signal: expect.any(AbortSignal) }),
    )
    expect(result.items).toHaveLength(1)
    expect(result.items[0].name).toBe('Public API')
    expect(result.totalElements).toBe(1)
  })

  it('surfaces the server error envelope', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse(
        { code: 'MONITOR_NOT_FOUND', message: 'monitor 7 was not found', traceId: 'trace-7' },
        404,
      ),
    )

    await expect(getMonitor(7)).rejects.toMatchObject({
      name: 'ApiError',
      code: 'MONITOR_NOT_FOUND',
      message: 'monitor 7 was not found',
      traceId: 'trace-7',
      status: 404,
    })
  })

  it('maps network failures to a stable error code', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(getMonitor(1)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      message: '网络请求失败',
      status: null,
      traceId: null,
    })
  })

  it('maps aborted requests to a timeout error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(
      new DOMException('The operation was aborted.', 'AbortError'),
    )

    await expect(getMonitor(1)).rejects.toMatchObject({
      code: 'TIMEOUT',
      message: '请求超时',
      status: null,
      traceId: null,
    })
  })

  it('maps response body aborts to a timeout error', async () => {
    const response = new Response(null, { status: 200 })
    vi.spyOn(response, 'text').mockRejectedValue(
      new DOMException('The operation was aborted.', 'AbortError'),
    )
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)

    const request = getMonitor(1)
    await expect(request).rejects.toBeInstanceOf(ApiError)
    await expect(request).rejects.toMatchObject({
      code: 'TIMEOUT',
      message: '请求超时',
      status: null,
      traceId: null,
    })
  })

  it('uses a generic error for non-JSON error responses', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('<html>bad gateway</html>', {
        status: 502,
        headers: { 'Content-Type': 'text/html' },
      }),
    )

    await expect(getMonitor(1)).rejects.toMatchObject({
      code: 'UNKNOWN_ERROR',
      message: '请求失败（HTTP 502）',
      status: 502,
      traceId: null,
    })
  })

  it('rejects successful responses that are not JSON', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('<html>ok</html>', {
        status: 200,
        headers: { 'Content-Type': 'text/html' },
      }),
    )

    await expect(getMonitor(1)).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      message: '响应不是有效的 JSON',
      status: 200,
      traceId: null,
    })
  })

  it('returns nothing for 204 responses', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 204 }))

    await expect(deleteMonitor(3)).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors/3',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('creates a monitor with a JSON body', async () => {
    const input = { name: 'Nightly job', type: 'HEARTBEAT' as const, intervalSeconds: 300 }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ id: 9, name: 'Nightly job', type: 'HEARTBEAT', heartbeatToken: 'tok-9' }, 201),
    )

    const created = await createMonitor(input)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      }),
    )
    expect(created.heartbeatToken).toBe('tok-9')
  })

  it('updates a monitor with a JSON body', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(jsonResponse({ id: 4, name: 'Renamed' }))

    await updateMonitor(4, { name: 'Renamed' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors/4',
      expect.objectContaining({
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'Renamed' }),
      }),
    )
  })

  it('builds check history pagination parameters', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(jsonResponse({ items: [], page: 2, size: 10, totalElements: 0 }))

    await listChecks(5, 2, 10)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors/5/checks?page=2&size=10',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('builds incident filters and omits empty values', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() =>
        Promise.resolve(jsonResponse({ items: [], page: 0, size: 20, totalElements: 0 })),
      )

    await listIncidents({ status: 'OPEN', monitorId: 3, page: 1, size: 10 })
    await listIncidents()

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/incidents?status=OPEN&monitor_id=3&page=1&size=10',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/incidents?page=0&size=20',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('rotates a heartbeat token', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(jsonResponse({ heartbeatToken: 'rotated-token' }))

    await expect(rotateHeartbeatToken(6)).resolves.toEqual({ heartbeatToken: 'rotated-token' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors/6/heartbeat-token',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('revokes a heartbeat token', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 204 }))

    await expect(revokeHeartbeatToken(6)).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors/6/heartbeat-token',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('loads an incident with its deliveries', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({
        id: 2,
        monitorId: 5,
        monitorName: 'Nightly job',
        startedAt: '2026-10-24T08:00:00Z',
        resolvedAt: null,
        status: 'OPEN',
        failureType: 'HEARTBEAT_TIMEOUT',
        summary: 'heartbeat timed out',
        deliveries: [{ id: 8, status: 'PENDING', attemptCount: 1 }],
      }),
    )

    const incident = await getIncident(2)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/incidents/2',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(incident.deliveries[0].status).toBe('PENDING')
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
