import { flushPromises } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getSession, setUnauthorizedHandler } from '../api/client'
import { applySession, authState, resetAuthState } from '../auth/session'
import { createAppRouter } from './index'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    getSession: vi.fn(),
    setUnauthorizedHandler: vi.fn(),
  }
})

const getSessionMock = vi.mocked(getSession)
const setUnauthorizedHandlerMock = vi.mocked(setUnauthorizedHandler)

describe('auth routing', () => {
  beforeEach(() => {
    resetAuthState()
    vi.clearAllMocks()
    getSessionMock.mockResolvedValue({
      authenticated: false,
      username: null,
      role: null,
      csrfToken: 'csrf-1',
    })
  })

  it('redirects anonymous users to login and keeps the original path', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/monitors?page=2')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/monitors?page=2')
    expect(router.currentRoute.value.query.expired).toBeUndefined()
  })

  it('protects settings and incident routes', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/settings')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/login')

    await router.push('/incidents')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/incidents')
  })

  it('lets authenticated users into management routes', async () => {
    applySession({
      authenticated: true,
      username: 'admin',
      role: 'ADMIN',
      csrfToken: 'csrf-1',
    })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/monitors')
    await router.isReady()

    expect(getSessionMock).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/monitors')
  })

  it('sends authenticated users away from the login page', async () => {
    applySession({
      authenticated: true,
      username: 'admin',
      role: 'ADMIN',
      csrfToken: 'csrf-1',
    })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/login')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/monitors')
  })

  it('clears auth and redirects with expired=1 when a request is unauthorized', async () => {
    applySession({
      authenticated: true,
      username: 'admin',
      role: 'ADMIN',
      csrfToken: 'csrf-1',
    })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/incidents')
    await router.isReady()

    const handler = setUnauthorizedHandlerMock.mock.calls.at(-1)?.[0]
    expect(handler).toBeTypeOf('function')
    handler?.()
    await flushPromises()

    expect(authState.authenticated).toBe(false)
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/incidents')
    expect(router.currentRoute.value.query.expired).toBe('1')
  })
})
