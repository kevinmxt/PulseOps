import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { loginErrorMessage } from './authError'

describe('loginErrorMessage', () => {
  it('uses a generic message for invalid credentials', () => {
    const error = new ApiError('invalid username or password', {
      code: 'INVALID_CREDENTIALS',
      status: 401,
    })

    expect(loginErrorMessage(error)).toBe('用户名或密码错误')
  })

  it('shows the retry delay when the server sends one', () => {
    const error = new ApiError('too many attempts', {
      code: 'RATE_LIMITED',
      status: 429,
      retryAfterSeconds: 42,
    })

    expect(loginErrorMessage(error)).toBe('尝试次数过多，请在 42 秒后重试')
  })

  it('falls back when no retry delay is available', () => {
    const error = new ApiError('too many attempts', {
      code: 'RATE_LIMITED',
      status: 429,
    })

    expect(loginErrorMessage(error)).toBe('尝试次数过多，请稍后重试')
  })

  it('preserves other server messages and hides unexpected errors', () => {
    expect(
      loginErrorMessage(
        new ApiError('服务器内部错误', { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    ).toBe('服务器内部错误')
    expect(loginErrorMessage(new Error('boom'))).toBe('登录失败，请稍后重试')
  })
})
