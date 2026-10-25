import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  changePassword as changePasswordRequest,
  getSession,
  login as loginRequest,
  logout as logoutRequest,
  setCsrfToken,
} from '../api/client'
import {
  applySession,
  authState,
  changePassword,
  ensureSession,
  login,
  logout,
  resetAuthState,
} from './session'

vi.mock('../api/client', () => ({
  getSession: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  setCsrfToken: vi.fn(),
}))

const getSessionMock = vi.mocked(getSession)
const loginMock = vi.mocked(loginRequest)
const logoutMock = vi.mocked(logoutRequest)
const changePasswordMock = vi.mocked(changePasswordRequest)
const setCsrfTokenMock = vi.mocked(setCsrfToken)

describe('auth session', () => {
  beforeEach(() => {
    resetAuthState()
    vi.clearAllMocks()
  })

  it('bootstraps the session once and stores the CSRF token', async () => {
    getSessionMock.mockResolvedValue({
      authenticated: false,
      username: null,
      role: null,
      csrfToken: 'csrf-1',
    })

    await ensureSession()
    await ensureSession()

    expect(getSessionMock).toHaveBeenCalledTimes(1)
    expect(authState.initialized).toBe(true)
    expect(authState.authenticated).toBe(false)
    expect(setCsrfTokenMock).toHaveBeenCalledWith('csrf-1')
  })

  it('treats a failed bootstrap as anonymous instead of blocking navigation', async () => {
    getSessionMock.mockRejectedValue(new Error('network down'))

    await ensureSession()

    expect(authState.initialized).toBe(true)
    expect(authState.authenticated).toBe(false)
  })

  it('stores the authenticated user after a successful login', async () => {
    loginMock.mockResolvedValue({ username: 'admin', role: 'ADMIN' })

    await login('admin', 'secret-password')

    expect(loginMock).toHaveBeenCalledWith('admin', 'secret-password')
    expect(authState).toMatchObject({
      initialized: true,
      authenticated: true,
      username: 'admin',
      role: 'ADMIN',
    })
  })

  it('clears the local session even when logout fails', async () => {
    applySession({
      authenticated: true,
      username: 'admin',
      role: 'ADMIN',
      csrfToken: 'csrf-1',
    })
    logoutMock.mockRejectedValue(new Error('network down'))

    await expect(logout()).rejects.toThrow('network down')

    expect(authState.authenticated).toBe(false)
    expect(authState.username).toBeNull()
  })

  it('clears the local session after a password change', async () => {
    applySession({
      authenticated: true,
      username: 'admin',
      role: 'ADMIN',
      csrfToken: 'csrf-1',
    })
    changePasswordMock.mockResolvedValue(undefined)

    await changePassword({ currentPassword: 'old-password-123', newPassword: 'new-password-123' })

    expect(changePasswordMock).toHaveBeenCalledWith({
      currentPassword: 'old-password-123',
      newPassword: 'new-password-123',
    })
    expect(authState.authenticated).toBe(false)
  })
})
