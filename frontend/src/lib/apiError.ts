import { ApiError } from '../api/client'

export interface ErrorDetails {
  code: string
  message: string
  traceId: string | null
}

export function toErrorDetails(error: unknown): ErrorDetails {
  if (error instanceof ApiError) {
    return {
      code: error.code,
      message: error.message,
      traceId: error.traceId,
    }
  }
  if (error instanceof Error) {
    return {
      code: 'UNKNOWN_ERROR',
      message: error.message,
      traceId: null,
    }
  }
  return {
    code: 'UNKNOWN_ERROR',
    message: '请求失败',
    traceId: null,
  }
}
