import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { toErrorDetails } from './apiError'

describe('toErrorDetails', () => {
  it('keeps the server error envelope', () => {
    const error = new ApiError('name must not be blank', {
      code: 'VALIDATION_ERROR',
      status: 400,
      traceId: 'trace-1',
    })

    expect(toErrorDetails(error)).toEqual({
      code: 'VALIDATION_ERROR',
      message: 'name must not be blank',
      traceId: 'trace-1',
    })
  })

  it('normalises unexpected errors', () => {
    expect(toErrorDetails(new Error('boom'))).toEqual({
      code: 'UNKNOWN_ERROR',
      message: 'boom',
      traceId: null,
    })
    expect(toErrorDetails('nope')).toEqual({
      code: 'UNKNOWN_ERROR',
      message: '请求失败',
      traceId: null,
    })
  })
})
