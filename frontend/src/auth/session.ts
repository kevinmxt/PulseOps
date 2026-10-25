import { reactive } from 'vue'
import {
  changePassword as changePasswordRequest,
  getSession,
  login as loginRequest,
  logout as logoutRequest,
  setCsrfToken,
} from '../api/client'
import type { PasswordChangeInput, SessionInfo } from '../api/types'

export interface AuthState {
  initialized: boolean
  authenticated: boolean
  username: string | null
  role: string | null
}

export const authState = reactive<AuthState>({
  initialized: false,
  authenticated: false,
  username: null,
  role: null,
})

function setAuth(authenticated: boolean, username: string | null, role: string | null): void {
  authState.initialized = true
  authState.authenticated = authenticated
  authState.username = username
  authState.role = role
}

export function applySession(session: SessionInfo): void {
  setAuth(session.authenticated, session.username, session.role)
  setCsrfToken(session.csrfToken)
}

export function clearAuth(): void {
  // Keep the CSRF token: the login form still needs it after a session expires.
  setAuth(false, null, null)
}

export function resetAuthState(): void {
  setAuth(false, null, null)
  authState.initialized = false
  setCsrfToken(null)
}

export async function ensureSession(): Promise<AuthState> {
  if (authState.initialized) {
    return authState
  }
  try {
    applySession(await getSession())
  } catch {
    clearAuth()
  }
  return authState
}

export async function login(username: string, password: string): Promise<void> {
  const result = await loginRequest(username, password)
  setAuth(true, result.username, result.role)
}

export async function logout(): Promise<void> {
  try {
    await logoutRequest()
  } finally {
    clearAuth()
  }
}

export async function changePassword(input: PasswordChangeInput): Promise<void> {
  await changePasswordRequest(input)
  clearAuth()
}
