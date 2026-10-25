import { applySession, resetAuthState } from '../auth/session'

export function authenticateForTest(username = 'test-admin', csrfToken = 'csrf-1'): void {
  resetAuthState()
  applySession({
    authenticated: true,
    username,
    role: 'ADMIN',
    csrfToken,
  })
}
