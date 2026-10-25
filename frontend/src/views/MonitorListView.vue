<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { deleteMonitor, listMonitors, updateMonitor } from '../api/client'
import type { Monitor, PageResponse } from '../api/types'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import PaginationControls from '../components/PaginationControls.vue'
import { useVisiblePolling } from '../composables/useVisiblePolling'
import { toErrorDetails, type ErrorDetails } from '../lib/apiError'
import { formatAbsoluteTime, formatLatency, formatRelativeTime } from '../lib/format'
import {
  lastKnownStatusLabel,
  statusClass,
  statusLabel,
  typeLabel,
} from '../lib/monitorDisplay'
import { monitorToInput } from '../lib/monitorInput'

const PAGE_SIZE = 20
const POLL_INTERVAL_MS = 30_000

const monitors = ref<Monitor[]>([])
const page = ref(0)
const totalElements = ref(0)
const loading = ref(true)
const refreshing = ref(false)
const error = ref<ErrorDetails | null>(null)
const actionBusyId = ref<number | null>(null)
const pendingDelete = ref<Monitor | null>(null)
let pendingReloadPage: number | null = null
let desiredPage = 0

function applyResult(result: PageResponse<Monitor>): void {
  monitors.value = result.items
  page.value = result.page
  totalElements.value = result.totalElements
  desiredPage = result.page
  error.value = null
}

async function load(targetPage = page.value, initial = false): Promise<void> {
  desiredPage = targetPage
  if (refreshing.value) {
    pendingReloadPage = targetPage
    return
  }
  refreshing.value = true
  if (initial || targetPage !== page.value) {
    loading.value = true
  }
  try {
    const result = await listMonitors(targetPage, PAGE_SIZE)
    const lastPage = Math.max(0, Math.ceil(result.totalElements / PAGE_SIZE) - 1)
    if (result.items.length === 0 && targetPage > lastPage) {
      applyResult(await listMonitors(lastPage, PAGE_SIZE))
    } else {
      applyResult(result)
    }
  } catch (cause) {
    error.value = toErrorDetails(cause)
  } finally {
    loading.value = false
    refreshing.value = false
    if (pendingReloadPage !== null) {
      const nextPage = pendingReloadPage
      pendingReloadPage = null
      void load(nextPage)
    }
  }
}

function refresh(): void {
  void load(desiredPage)
}

function goToPage(target: number): void {
  void load(target)
}

async function toggleMonitor(monitor: Monitor): Promise<void> {
  actionBusyId.value = monitor.id
  try {
    await updateMonitor(monitor.id, monitorToInput(monitor, !monitor.enabled))
    await load(page.value)
  } catch (cause) {
    error.value = toErrorDetails(cause)
  } finally {
    actionBusyId.value = null
  }
}

function requestDelete(monitor: Monitor): void {
  pendingDelete.value = monitor
}

async function confirmDelete(): Promise<void> {
  const monitor = pendingDelete.value
  if (!monitor || actionBusyId.value !== null) {
    return
  }
  actionBusyId.value = monitor.id
  try {
    await deleteMonitor(monitor.id)
    pendingDelete.value = null
    await load(page.value)
  } catch (cause) {
    error.value = toErrorDetails(cause)
  } finally {
    actionBusyId.value = null
  }
}

onMounted(() => {
  void load(0, true)
})

useVisiblePolling(refresh, POLL_INTERVAL_MS)
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <h1>监控项</h1>
        <p class="muted">共 {{ totalElements }} 个监控项</p>
      </div>
      <div class="page-actions">
        <RouterLink class="button button-primary" to="/monitors/new">新建监控项</RouterLink>
        <button class="button" type="button" :disabled="refreshing" @click="refresh">
          {{ refreshing ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="error-panel">
      <div>
        <strong class="mono">{{ error.code }}</strong>
        <p>{{ error.message }}</p>
        <p v-if="error.traceId" class="mono muted">traceId: {{ error.traceId }}</p>
      </div>
      <button class="button" type="button" @click="refresh">重试</button>
    </div>

    <p v-if="loading" class="muted">加载中…</p>
    <div v-else-if="monitors.length === 0 && !error" class="empty-state">暂无监控项</div>

    <template v-else-if="monitors.length > 0">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>状态</th>
            <th>最近检查</th>
            <th>延迟</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="monitor in monitors" :key="monitor.id">
            <td>
              <RouterLink :to="`/monitors/${monitor.id}`">{{ monitor.name }}</RouterLink>
            </td>
            <td>
              <span class="type-badge">{{ typeLabel(monitor.type) }}</span>
            </td>
            <td>
              <span
                class="status-badge"
                :class="statusClass(monitor.currentStatus, monitor.enabled)"
              >
                {{ statusLabel(monitor.currentStatus, monitor.enabled) }}
              </span>
              <span v-if="!monitor.enabled" class="status-note muted">
                最后状态：{{ lastKnownStatusLabel(monitor.currentStatus) }}
              </span>
            </td>
            <td>
              <span :title="formatAbsoluteTime(monitor.lastCheckedAt)">
                {{ formatRelativeTime(monitor.lastCheckedAt) }}
              </span>
            </td>
            <td class="mono">{{ formatLatency(monitor.lastCheckDurationMs) }}</td>
            <td class="row-actions">
              <RouterLink class="button button-link" :to="`/monitors/${monitor.id}/edit`">
                编辑
              </RouterLink>
              <button
                class="button"
                type="button"
                :disabled="actionBusyId === monitor.id"
                @click="toggleMonitor(monitor)"
              >
                {{ monitor.enabled ? '禁用' : '启用' }}
              </button>
              <button
                class="button button-danger-outline"
                type="button"
                :disabled="actionBusyId === monitor.id"
                @click="requestDelete(monitor)"
              >
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <PaginationControls
        :page="page"
        :size="PAGE_SIZE"
        :total-elements="totalElements"
        :disabled="refreshing"
        @change="goToPage"
      />
    </template>

    <ConfirmDialog
      :open="pendingDelete !== null"
      title="删除监控项"
      :message="`确定删除「${pendingDelete?.name ?? ''}」吗？历史结果与心跳凭证会一并删除。`"
      confirm-label="确认删除"
      :busy="pendingDelete !== null && actionBusyId === pendingDelete.id"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </section>
</template>
