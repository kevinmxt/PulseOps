import {
  createRouter,
  createWebHistory,
  type RouteRecordRaw,
  type RouterHistory,
} from 'vue-router'
import { setUnauthorizedHandler } from '../api/client'
import { authState, clearAuth, ensureSession } from '../auth/session'
import IncidentListView from '../views/IncidentListView.vue'
import LoginView from '../views/LoginView.vue'
import MonitorCreateView from '../views/MonitorCreateView.vue'
import MonitorDetailView from '../views/MonitorDetailView.vue'
import MonitorEditView from '../views/MonitorEditView.vue'
import MonitorListView from '../views/MonitorListView.vue'
import NotFoundView from '../views/NotFoundView.vue'
import SettingsView from '../views/SettingsView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
  }
}

export const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginView },
  { path: '/', redirect: '/monitors' },
  { path: '/monitors', name: 'monitors', component: MonitorListView, meta: { requiresAuth: true } },
  {
    path: '/monitors/new',
    name: 'monitor-create',
    component: MonitorCreateView,
    meta: { requiresAuth: true },
  },
  {
    path: '/monitors/:id',
    name: 'monitor-detail',
    component: MonitorDetailView,
    props: true,
    meta: { requiresAuth: true },
  },
  {
    path: '/monitors/:id/edit',
    name: 'monitor-edit',
    component: MonitorEditView,
    props: true,
    meta: { requiresAuth: true },
  },
  {
    path: '/incidents',
    name: 'incidents',
    component: IncidentListView,
    meta: { requiresAuth: true },
  },
  { path: '/settings', name: 'settings', component: SettingsView, meta: { requiresAuth: true } },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
]

export function createAppRouter(
  history: RouterHistory = createWebHistory(import.meta.env.BASE_URL),
) {
  const router = createRouter({ history, routes })

  router.beforeEach(async (to) => {
    await ensureSession()
    if (to.name === 'login') {
      return authState.authenticated ? { path: '/monitors' } : true
    }
    if (to.meta.requiresAuth && !authState.authenticated) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    return true
  })

  setUnauthorizedHandler(() => {
    clearAuth()
    const current = router.currentRoute.value
    if (current.path === '/login') {
      return
    }
    void router.replace({
      path: '/login',
      query: { redirect: current.fullPath, expired: '1' },
    })
  })

  return router
}
