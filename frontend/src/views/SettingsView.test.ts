import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { changePassword } from '../auth/session'
import SettingsView from './SettingsView.vue'

vi.mock('../auth/session', () => ({
  changePassword: vi.fn(),
}))

const changePasswordMock = vi.mocked(changePassword)

const EmptyView = { template: '<div />' }

async function mountSettings() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/settings', component: SettingsView },
      { path: '/login', component: EmptyView },
    ],
  })
  await router.push('/settings')
  await router.isReady()
  const wrapper = mount(SettingsView, { global: { plugins: [router] } })
  return { router, wrapper }
}

async function fillForm(
  wrapper: Awaited<ReturnType<typeof mountSettings>>['wrapper'],
  current = 'old-password-123',
  next = 'new-password-123',
  confirm = 'new-password-123',
) {
  await wrapper.find('#current-password').setValue(current)
  await wrapper.find('#new-password').setValue(next)
  await wrapper.find('#confirm-password').setValue(confirm)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('SettingsView', () => {
  beforeEach(() => {
    changePasswordMock.mockReset()
  })

  it('requires the current password', async () => {
    const { wrapper } = await mountSettings()

    await fillForm(wrapper, '')

    expect(wrapper.text()).toContain('请输入当前密码')
    expect(changePasswordMock).not.toHaveBeenCalled()
  })

  it('enforces the password length and confirmation rules', async () => {
    const { wrapper } = await mountSettings()

    await fillForm(wrapper, 'old-password-123', 'short', 'short')
    expect(wrapper.text()).toContain('12–128 个字符')

    await fillForm(wrapper, 'old-password-123', 'new-password-123', 'different-password')
    expect(wrapper.text()).toContain('两次输入的新密码不一致')

    await fillForm(wrapper, 'old-password-123', 'old-password-123', 'old-password-123')
    expect(wrapper.text()).toContain('新密码不能与当前密码相同')
    expect(changePasswordMock).not.toHaveBeenCalled()
  })

  it('changes the password and redirects to the login page', async () => {
    changePasswordMock.mockResolvedValue(undefined)
    const { router, wrapper } = await mountSettings()

    await fillForm(wrapper)

    expect(changePasswordMock).toHaveBeenCalledWith({
      currentPassword: 'old-password-123',
      newPassword: 'new-password-123',
    })
    expect(router.currentRoute.value.fullPath).toBe('/login?changed=1')
  })

  it('maps an invalid current password to a field error', async () => {
    changePasswordMock.mockRejectedValue(
      new ApiError('current password is incorrect', {
        code: 'INVALID_CURRENT_PASSWORD',
        status: 400,
      }),
    )
    const { wrapper } = await mountSettings()

    await fillForm(wrapper)

    expect(wrapper.text()).toContain('当前密码不正确')
  })

  it('shows other server errors with their trace id', async () => {
    changePasswordMock.mockRejectedValue(
      new ApiError('unexpected error', {
        code: 'INTERNAL_ERROR',
        status: 500,
        traceId: 'trace-9',
      }),
    )
    const { wrapper } = await mountSettings()

    await fillForm(wrapper)

    expect(wrapper.text()).toContain('INTERNAL_ERROR')
    expect(wrapper.text()).toContain('unexpected error')
    expect(wrapper.text()).toContain('trace-9')
  })
})
