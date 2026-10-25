import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { logout, setUnauthorizedHandler } from './api/client'
import App from './App.vue'
import { clearAuth } from './auth/session'
import { createAppRouter } from './router'
import { authenticateForTest } from './test/auth'

vi.mock('./api/client', async () => {
  const actual = await vi.importActual<typeof import('./api/client')>('./api/client')
  return {
    ...actual,
    logout: vi.fn().mockResolvedValue(undefined),
  }
})

const logoutMock = vi.mocked(logout)

async function mountAt(path: string) {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [router] } })
  return { router, wrapper }
}

describe('app navigation and authentication', () => {
  beforeEach(() => {
    authenticateForTest('admin')
    logoutMock.mockClear()
  })

  afterEach(() => {
    setUnauthorizedHandler(null)
  })

  it('shows the monitor and incident navigation links', async () => {
    const { wrapper } = await mountAt('/monitors')
    const links = wrapper.findAll('nav a').map((link) => link.text())

    expect(links).toEqual(['监控项', 'Incidents'])
  })

  it('renders each management route', async () => {
    expect((await mountAt('/monitors')).wrapper.find('h1').text()).toBe('监控项')
    expect((await mountAt('/monitors/new')).wrapper.find('h1').text()).toBe('创建监控项')

    const detail = await mountAt('/monitors/12')
    expect(detail.wrapper.find('h1').text()).toBe('监控项详情')
    expect(detail.wrapper.text()).toContain('12')

    const edit = await mountAt('/monitors/12/edit')
    expect(edit.wrapper.find('h1').text()).toBe('编辑监控项')
    expect(edit.wrapper.text()).toContain('12')

    expect((await mountAt('/incidents')).wrapper.find('h1').text()).toBe('Incidents')
    expect((await mountAt('/settings')).wrapper.find('h1').text()).toBe('设置')
  })

  it('shows a not-found page for unknown routes', async () => {
    const { wrapper } = await mountAt('/does-not-exist')

    expect(wrapper.find('h1').text()).toBe('未找到页面')
  })

  it('shows the username, settings link and logout in the header', async () => {
    const { wrapper } = await mountAt('/monitors')

    expect(wrapper.find('.app-user').text()).toContain('admin')
    expect(wrapper.find('.app-user a[href="/settings"]').text()).toBe('设置')
    expect(wrapper.find('.app-user button').text()).toBe('登出')
  })

  it('logs out and returns to the login page', async () => {
    const { router, wrapper } = await mountAt('/incidents')

    const logoutButton = wrapper.findAll('button').find((button) => button.text() === '登出')
    await logoutButton?.trigger('click')
    await flushPromises()

    expect(logoutMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper.find('.app-header').exists()).toBe(false)
    expect(wrapper.text()).toContain('登录 PulseOps')
  })

  it('returns to the login page even when the logout request fails', async () => {
    logoutMock.mockRejectedValueOnce(new Error('network down'))
    const { router, wrapper } = await mountAt('/monitors')

    const logoutButton = wrapper.findAll('button').find((button) => button.text() === '登出')
    await logoutButton?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper.text()).toContain('登录 PulseOps')
  })

  it('shows the public login page without the management header', async () => {
    clearAuth()
    const { wrapper } = await mountAt('/login')

    expect(wrapper.find('h1').text()).toBe('登录 PulseOps')
    expect(wrapper.find('.app-header').exists()).toBe(false)
  })

  it('sends an authenticated visitor from login to the monitor list', async () => {
    const { router, wrapper } = await mountAt('/login')

    expect(router.currentRoute.value.path).toBe('/monitors')
    expect(wrapper.find('h1').text()).toBe('监控项')
  })
})
