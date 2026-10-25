import type {
  CheckResult,
  HeartbeatTokenResponse,
  Incident,
  IncidentDetail,
  IncidentQuery,
  Monitor,
  MonitorInput,
  PageResponse,
} from './types'

interface ApiErrorEnvelope {
  code: string
  message: string
  traceId: string | null
}

const REQUEST_TIMEOUT_MS = 15_000

export class ApiError extends Error {
  readonly code: string
  readonly status: number | null
  readonly traceId: string | null

  constructor(
    message: string,
    options: { code: string; status: number | null; traceId?: string | null; cause?: unknown },
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'ApiError'
    this.code = options.code
    this.status = options.status
    this.traceId = options.traceId ?? null
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const { init: requestInit, cancelTimeout } = withTimeout(init)
  try {
    let response: Response
    try {
      response = await fetch(path, requestInit)
    } catch (cause) {
      throw networkError(cause)
    }
    if (response.status === 204) {
      return undefined as T
    }
    const payload = await readJson(response)
    if (!response.ok) {
      const envelope = errorEnvelope(payload)
      throw new ApiError(envelope?.message ?? `请求失败（HTTP ${response.status}）`, {
        code: envelope?.code ?? 'UNKNOWN_ERROR',
        status: response.status,
        traceId: envelope?.traceId ?? null,
      })
    }
    if (payload === null) {
      throw new ApiError('响应不是有效的 JSON', {
        code: 'INVALID_RESPONSE',
        status: response.status,
        traceId: null,
      })
    }
    return payload as T
  } finally {
    cancelTimeout()
  }
}

function networkError(cause: unknown): ApiError {
  const timedOut = cause instanceof DOMException && cause.name === 'AbortError'
  return new ApiError(timedOut ? '请求超时' : '网络请求失败', {
    code: timedOut ? 'TIMEOUT' : 'NETWORK_ERROR',
    status: null,
    traceId: null,
    cause,
  })
}

function withTimeout(init: RequestInit): { init: RequestInit; cancelTimeout: () => void } {
  if (init.signal) {
    return { init, cancelTimeout: () => {} }
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  return {
    init: { ...init, signal: controller.signal },
    cancelTimeout: () => clearTimeout(timer),
  }
}

async function readJson(response: Response): Promise<unknown> {
  let text: string
  try {
    text = await response.text()
  } catch (cause) {
    throw networkError(cause)
  }
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function errorEnvelope(payload: unknown): ApiErrorEnvelope | null {
  if (typeof payload !== 'object' || payload === null) {
    return null
  }
  const record = payload as Record<string, unknown>
  if (typeof record.code !== 'string' || typeof record.message !== 'string') {
    return null
  }
  return {
    code: record.code,
    message: record.message,
    traceId: typeof record.traceId === 'string' ? record.traceId : null,
  }
}

export function listMonitors(page = 0, size = 20): Promise<PageResponse<Monitor>> {
  return request<PageResponse<Monitor>>(
    `/api/v1/monitors?page=${page}&size=${size}`,
    { method: 'GET' },
  )
}

export function getMonitor(id: number): Promise<Monitor> {
  return request<Monitor>(`/api/v1/monitors/${id}`, { method: 'GET' })
}

export function deleteMonitor(id: number): Promise<void> {
  return request<void>(`/api/v1/monitors/${id}`, { method: 'DELETE' })
}

function jsonRequest<T>(path: string, method: 'POST' | 'PUT', body: unknown): Promise<T> {
  return request<T>(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function createMonitor(input: MonitorInput): Promise<Monitor> {
  return jsonRequest<Monitor>('/api/v1/monitors', 'POST', input)
}

export function updateMonitor(id: number, input: MonitorInput): Promise<Monitor> {
  return jsonRequest<Monitor>(`/api/v1/monitors/${id}`, 'PUT', input)
}

export function listChecks(
  monitorId: number,
  page = 0,
  size = 20,
): Promise<PageResponse<CheckResult>> {
  return request<PageResponse<CheckResult>>(
    `/api/v1/monitors/${monitorId}/checks?page=${page}&size=${size}`,
    { method: 'GET' },
  )
}

export function listIncidents(query: IncidentQuery = {}): Promise<PageResponse<Incident>> {
  const params = new URLSearchParams()
  if (query.status) {
    params.set('status', query.status)
  }
  if (query.monitorId !== undefined) {
    params.set('monitor_id', String(query.monitorId))
  }
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? 20))
  return request<PageResponse<Incident>>(`/api/v1/incidents?${params.toString()}`, {
    method: 'GET',
  })
}

export function getIncident(id: number): Promise<IncidentDetail> {
  return request<IncidentDetail>(`/api/v1/incidents/${id}`, { method: 'GET' })
}

export function rotateHeartbeatToken(id: number): Promise<HeartbeatTokenResponse> {
  return request<HeartbeatTokenResponse>(`/api/v1/monitors/${id}/heartbeat-token`, {
    method: 'POST',
  })
}

export function revokeHeartbeatToken(id: number): Promise<void> {
  return request<void>(`/api/v1/monitors/${id}/heartbeat-token`, { method: 'DELETE' })
}
