import { ApiError } from '../api/client'

export function loginErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '登录失败，请稍后重试'
  }
  if (error.status === 401 || error.code === 'INVALID_CREDENTIALS') {
    return '用户名或密码错误'
  }
  if (error.status === 429 || error.code === 'RATE_LIMITED') {
    return error.retryAfterSeconds === null
      ? '尝试次数过多，请稍后重试'
      : `尝试次数过多，请在 ${error.retryAfterSeconds} 秒后重试`
  }
  return error.message
}
