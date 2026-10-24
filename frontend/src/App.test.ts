import { mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import App from './App.vue'
import { createAppRouter } from './router'

async function mountAt(path: string) {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  return mount(App, { global: { plugins: [router] } })
}

describe('app navigation', () => {
  it('shows the monitor and incident navigation links', async () => {
    const wrapper = await mountAt('/monitors')
    const links = wrapper.findAll('nav a').map((link) => link.text())

    expect(links).toEqual(['监控项', 'Incidents'])
  })

  it('renders each management route', async () => {
    expect((await mountAt('/monitors')).find('h1').text()).toBe('监控项')
    expect((await mountAt('/monitors/new')).find('h1').text()).toBe('创建监控项')

    const detail = await mountAt('/monitors/12')
    expect(detail.find('h1').text()).toBe('监控项详情')
    expect(detail.text()).toContain('12')

    const edit = await mountAt('/monitors/12/edit')
    expect(edit.find('h1').text()).toBe('编辑监控项')
    expect(edit.text()).toContain('12')

    expect((await mountAt('/incidents')).find('h1').text()).toBe('Incidents')
  })

  it('shows a not-found page for unknown routes', async () => {
    const wrapper = await mountAt('/does-not-exist')

    expect(wrapper.find('h1').text()).toBe('未找到页面')
  })
})
