import {
  createRouter,
  createWebHistory,
  type RouteRecordRaw,
  type RouterHistory,
} from 'vue-router'
import IncidentListView from '../views/IncidentListView.vue'
import MonitorCreateView from '../views/MonitorCreateView.vue'
import MonitorDetailView from '../views/MonitorDetailView.vue'
import MonitorEditView from '../views/MonitorEditView.vue'
import MonitorListView from '../views/MonitorListView.vue'
import NotFoundView from '../views/NotFoundView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/monitors' },
  { path: '/monitors', name: 'monitors', component: MonitorListView },
  { path: '/monitors/new', name: 'monitor-create', component: MonitorCreateView },
  {
    path: '/monitors/:id',
    name: 'monitor-detail',
    component: MonitorDetailView,
    props: true,
  },
  {
    path: '/monitors/:id/edit',
    name: 'monitor-edit',
    component: MonitorEditView,
    props: true,
  },
  { path: '/incidents', name: 'incidents', component: IncidentListView },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
]

export function createAppRouter(
  history: RouterHistory = createWebHistory(import.meta.env.BASE_URL),
) {
  return createRouter({ history, routes })
}
