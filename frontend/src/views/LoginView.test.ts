import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { login } from '../auth/session'
import LoginView from './LoginView.vue'

vi.mock('../auth/session', () => ({
  login: vi.fn(),
}))

const loginMock = vi.mocked(login)

const EmptyView = { template: '<div />' }

async function mountLogin(path = '/login') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/monitors', component: EmptyView },
      { path: '/monitors/:id', component: EmptyView },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(LoginView, { global: { plugins: [router] } })
  return { router, wrapper }
}

async function submitCredentials(
  wrapper: Awaited<ReturnType<typeof mountLogin>>['wrapper'],
  username = 'admin',
  password = 'secret-password',
) {
  await wrapper.find('#login-username').setValue(username)
  await wrapper.find('#login-password').setValue(password)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('LoginView', () => {
  beforeEach(() => {
    loginMock.mockReset()
  })

  it('returns to the requested internal path after login', async () => {
    loginMock.mockResolvedValue(undefined)
    const { router, wrapper } = await mountLogin('/login?redirect=/monitors/7')

    await submitCredentials(wrapper)

    expect(loginMock).toHaveBeenCalledWith('admin', 'secret-password')
    expect(router.currentRoute.value.fullPath).toBe('/monitors/7')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('rejects external redirect targets and falls back to the monitor list', async () => {
    loginMock.mockResolvedValue(undefined)
    const { router, wrapper } = await mountLogin('/login?redirect=//evil.example')

    await submitCredentials(wrapper)

    expect(router.currentRoute.value.fullPath).toBe('/monitors')
  })

  it('shows a generic message for invalid credentials', async () => {
    loginMock.mockRejectedValue(
      new ApiError('invalid username or password', {
        code: 'INVALID_CREDENTIALS',
        status: 401,
        traceId: 'trace-1',
      }),
    )
    const { wrapper } = await mountLogin()

    await submitCredentials(wrapper)

    expect(wrapper.text()).toContain('用户名或密码错误')
    expect(wrapper.text()).not.toContain('trace-1')
  })

  it('shows the retry delay for rate-limited logins', async () => {
    loginMock.mockRejectedValue(
      new ApiError('too many attempts', {
        code: 'RATE_LIMITED',
        status: 429,
        retryAfterSeconds: 42,
      }),
    )
    const { wrapper } = await mountLogin()

    await submitCredentials(wrapper)

    expect(wrapper.text()).toContain('42 秒后重试')
  })

  it('validates that both fields are provided', async () => {
    const { wrapper } = await mountLogin()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('请输入用户名和密码')
    expect(loginMock).not.toHaveBeenCalled()
  })

  it('shows the expired and changed notices from the query string', async () => {
    const expired = await mountLogin('/login?expired=1')
    expect(expired.wrapper.text()).toContain('登录状态已过期')

    const changed = await mountLogin('/login?changed=1')
    expect(changed.wrapper.text()).toContain('密码已修改')
  })
})
