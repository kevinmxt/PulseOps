<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getIncident, listIncidents } from '../api/client'
import type {
  Incident,
  IncidentStatus,
  NotificationDelivery,
} from '../api/types'
import PaginationControls from '../components/PaginationControls.vue'
import ServerErrorPanel from '../components/ServerErrorPanel.vue'
import { toErrorDetails, type ErrorDetails } from '../lib/apiError'
import { formatAbsoluteTime } from '../lib/format'
import {
  deliveryChannelLabel,
  deliveryEventLabel,
  deliveryStatusClass,
  deliveryStatusLabel,
  displayOrDash,
  incidentStatusClass,
  incidentStatusLabel,
} from '../lib/incidentDisplay'

const PAGE_SIZE = 20

const route = useRoute()
const status = ref<IncidentStatus>('OPEN')
const incidents = ref<Incident[]>([])
const page = ref(0)
const totalElements = ref(0)
const loading = ref(true)
const error = ref<ErrorDetails | null>(null)

const expandedId = ref<number | null>(null)
const deliveries = ref<NotificationDelivery[]>([])
const deliveryLoading = ref(false)
const deliveryError = ref<ErrorDetails | null>(null)
let listRequestId = 0
let deliveryRequestId = 0

const monitorId = computed<number | null>(() => {
  const raw = route.query.monitor_id
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string') {
    return null
  }
  const id = Number(value)
  return Number.isInteger(id) && id > 0 ? id : null
})

async function load(targetPage = page.value, initial = false): Promise<void> {
  const requestId = ++listRequestId
  if (initial || targetPage !== page.value) {
    loading.value = true
  }
  error.value = null
  expandedId.value = null
  deliveries.value = []
  deliveryError.value = null
  deliveryRequestId += 1
  deliveryLoading.value = false
  try {
    const result = await listIncidents({
      status: status.value,
      monitorId: monitorId.value ?? undefined,
      page: targetPage,
      size: PAGE_SIZE,
    })
    if (requestId !== listRequestId) {
      return
    }
    incidents.value = result.items
    page.value = result.page
    totalElements.value = result.totalElements
  } catch (cause) {
    if (requestId !== listRequestId) {
      return
    }
    error.value = toErrorDetails(cause)
  } finally {
    if (requestId === listRequestId) {
      loading.value = false
    }
  }
}

function refresh(): void {
  void load(page.value, true)
}

function goToPage(target: number): void {
  void load(target)
}

async function loadDeliveries(incidentId: number): Promise<void> {
  const requestId = ++deliveryRequestId
  deliveryLoading.value = true
  deliveryError.value = null
  try {
    const detail = await getIncident(incidentId)
    if (requestId !== deliveryRequestId) {
      return
    }
    deliveries.value = detail.deliveries
  } catch (cause) {
    if (requestId !== deliveryRequestId) {
      return
    }
    deliveryError.value = toErrorDetails(cause)
  } finally {
    if (requestId === deliveryRequestId) {
      deliveryLoading.value = false
    }
  }
}

function toggleIncident(incident: Incident): void {
  if (expandedId.value === incident.id) {
    expandedId.value = null
    deliveryRequestId += 1
    deliveryLoading.value = false
    return
  }
  expandedId.value = incident.id
  deliveries.value = []
  void loadDeliveries(incident.id)
}

function resetList(): void {
  incidents.value = []
  page.value = 0
  totalElements.value = 0
  error.value = null
}

watch(status, () => {
  resetList()
  void load(0, true)
})

watch(
  () => route.query.monitor_id,
  () => {
    resetList()
    void load(0, true)
  },
)

onMounted(() => {
  void load(0, true)
})
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <h1>Incidents</h1>
        <p v-if="monitorId" class="muted">
          仅显示监控项 #{{ monitorId }} ·
          <RouterLink to="/incidents">清除过滤</RouterLink>
        </p>
        <p v-else class="muted">按开始时间倒序展示。</p>
      </div>
      <div class="page-actions">
        <label class="inline-label" for="incident-status">状态</label>
        <select id="incident-status" v-model="status" class="select">
          <option value="OPEN">进行中</option>
          <option value="RESOLVED">已恢复</option>
        </select>
        <button class="button" type="button" :disabled="loading" @click="refresh">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
      </div>
    </header>

    <template v-if="error">
      <ServerErrorPanel :details="error" />
      <div class="page-actions">
        <button class="button" type="button" @click="refresh">重试</button>
      </div>
    </template>

    <p v-if="loading" class="muted">加载中…</p>
    <div v-else-if="incidents.length === 0 && !error" class="empty-state">暂无 Incident</div>

    <template v-else-if="incidents.length > 0">
      <table class="data-table">
        <thead>
          <tr>
            <th>监控项</th>
            <th>状态</th>
            <th>开始时间</th>
            <th>恢复时间</th>
            <th>失败类型</th>
            <th>摘要</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="incident in incidents" :key="incident.id">
            <tr>
              <td>
                <RouterLink :to="`/monitors/${incident.monitorId}`">
                  {{ incident.monitorName ?? `#${incident.monitorId}` }}
                </RouterLink>
              </td>
              <td>
                <span class="status-badge" :class="incidentStatusClass(incident.status)">
                  {{ incidentStatusLabel(incident.status) }}
                </span>
              </td>
              <td>{{ formatAbsoluteTime(incident.startedAt) }}</td>
              <td>{{ formatAbsoluteTime(incident.resolvedAt) }}</td>
              <td class="mono">{{ incident.failureType }}</td>
              <td>{{ incident.summary }}</td>
              <td>
                <button class="button" type="button" @click="toggleIncident(incident)">
                  {{ expandedId === incident.id ? '收起' : '展开' }}
                </button>
              </td>
            </tr>
            <tr v-if="expandedId === incident.id">
              <td colspan="7" class="delivery-panel">
                <p v-if="deliveryLoading" class="muted">加载中…</p>
                <template v-else-if="deliveryError">
                  <ServerErrorPanel :details="deliveryError" />
                  <button class="button" type="button" @click="loadDeliveries(incident.id)">
                    重试
                  </button>
                </template>
                <div v-else-if="deliveries.length === 0" class="empty-state">
                  暂无投递记录
                </div>
                <table v-else class="data-table">
                  <thead>
                    <tr>
                      <th>渠道</th>
                      <th>事件</th>
                      <th>状态</th>
                      <th>尝试次数</th>
                      <th>最后尝试</th>
                      <th>发送时间</th>
                      <th>错误摘要</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="delivery in deliveries" :key="delivery.id">
                      <td :title="delivery.channel">{{ deliveryChannelLabel(delivery.channel) }}</td>
                      <td class="mono" :title="delivery.eventType">
                        {{ deliveryEventLabel(delivery.eventType) }}
                      </td>
                      <td>
                        <span
                          class="status-badge"
                          :class="deliveryStatusClass(delivery.status)"
                        >
                          {{ deliveryStatusLabel(delivery.status) }}
                        </span>
                      </td>
                      <td>{{ delivery.attemptCount }}</td>
                      <td>{{ formatAbsoluteTime(delivery.lastAttemptAt) }}</td>
                      <td>{{ formatAbsoluteTime(delivery.sentAt) }}</td>
                      <td>{{ displayOrDash(delivery.lastErrorSummary) }}</td>
                    </tr>
                  </tbody>
                </table>
              </td>
            </tr>
          </template>
        </tbody>
      </table>

      <PaginationControls
        :page="page"
        :size="PAGE_SIZE"
        :total-elements="totalElements"
        :disabled="loading"
        @change="goToPage"
      />
    </template>
  </section>
</template>
