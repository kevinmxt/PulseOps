import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  changePassword,
  createMonitor,
  deleteMonitor,
  getIncident,
  getMonitor,
  getSession,
  listChecks,
  listIncidents,
  listMonitors,
  login,
  logout,
  revokeHeartbeatToken,
  rotateHeartbeatToken,
  setCsrfToken,
  setUnauthorizedHandler,
  updateMonitor,
} from './client'

describe('api client', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    setCsrfToken(null)
    setUnauthorizedHandler(null)
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
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

  it('bootstraps the session and exposes the CSRF token', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({
        authenticated: true,
        username: 'admin',
        role: 'ADMIN',
        csrfToken: 'csrf-1',
      }),
    )

    const session = await getSession()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/session',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(session).toMatchObject({ authenticated: true, username: 'admin', csrfToken: 'csrf-1' })
  })

  it('logs in and logs out through the auth endpoints with CSRF', async () => {
    setCsrfToken('csrf-1')
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ username: 'admin', role: 'ADMIN' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))

    await expect(login('admin', 'secret-password')).resolves.toEqual({
      username: 'admin',
      role: 'ADMIN',
    })
    await expect(logout()).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/auth/login',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ username: 'admin', password: 'secret-password' }),
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' }),
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/auth/logout',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' }),
      }),
    )
  })

  it('changes the password with a JSON body', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 204 }))
    const input = { currentPassword: 'old-password-123', newPassword: 'new-password-123' }

    await changePassword(input)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/password',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify(input) }),
    )
  })

  it('sends the CSRF header on writes when a token is available', async () => {
    setCsrfToken('csrf-1')
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(jsonResponse({ id: 3, name: 'API' }, 201))

    await createMonitor({ name: 'API' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' }),
      }),
    )
  })

  it('adds the CSRF header to auth writes and deletes', async () => {
    setCsrfToken('csrf-1')
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 204 }))

    await logout()
    await changePassword({ currentPassword: 'old-password-123', newPassword: 'new-password-123' })
    await deleteMonitor(3)

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/auth/logout',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' }),
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/auth/password',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' }),
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/monitors/3',
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' }),
      }),
    )
  })

  it('falls back to the XSRF-TOKEN cookie when no token is cached', async () => {
    document.cookie = 'XSRF-TOKEN=cookie-token; path=/'
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(jsonResponse({ id: 3, name: 'API' }, 201))

    await createMonitor({ name: 'API' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/monitors',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'cookie-token' }),
      }),
    )
  })

  it('refreshes the CSRF token and retries a write exactly once', async () => {
    setCsrfToken('stale')
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        jsonResponse({ code: 'CSRF_INVALID', message: 'bad token', traceId: 'trace-1' }, 403),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          authenticated: false,
          username: null,
          role: null,
          csrfToken: 'fresh',
        }),
      )
      .mockResolvedValueOnce(jsonResponse({ id: 9, name: 'API' }, 201))

    const created = await createMonitor({ name: 'API' })

    expect(created.id).toBe(9)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/auth/session',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/monitors',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'fresh' }),
      }),
    )
  })

  it('does not retry CSRF failures more than once', async () => {
    setCsrfToken('stale')
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        jsonResponse({ code: 'CSRF_INVALID', message: 'bad token', traceId: 'trace-1' }, 403),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          authenticated: false,
          username: null,
          role: null,
          csrfToken: 'fresh',
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ code: 'CSRF_INVALID', message: 'bad token', traceId: 'trace-2' }, 403),
      )

    await expect(createMonitor({ name: 'API' })).rejects.toMatchObject({ code: 'CSRF_INVALID' })
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('notifies the unauthorized handler for protected requests', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ code: 'UNAUTHORIZED', message: 'authentication is required', traceId: 't' }, 401),
    )

    await expect(listMonitors()).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('does not redirect on failed login or session bootstrap', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        jsonResponse(
          { code: 'INVALID_CREDENTIALS', message: 'invalid username or password', traceId: 't' },
          401,
        ),
      )
      .mockResolvedValueOnce(
        jsonResponse({ code: 'UNAUTHORIZED', message: 'authentication is required', traceId: 't' }, 401),
      )

    await expect(login('admin', 'wrong-password')).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
    })
    await expect(getSession()).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('parses Retry-After on rate-limited responses', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse(
        { code: 'RATE_LIMITED', message: 'too many attempts', traceId: 't' },
        429,
        { 'Retry-After': '42' },
      ),
    )

    await expect(login('admin', 'secret-password')).rejects.toMatchObject({
      code: 'RATE_LIMITED',
      retryAfterSeconds: 42,
    })
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

function jsonResponse(
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}
