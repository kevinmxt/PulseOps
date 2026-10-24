<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { listMonitors } from '../api/client'
import type { Monitor } from '../api/types'
import { formatAbsoluteTime, formatLatency, formatRelativeTime } from '../lib/format'
import {
  lastKnownStatusLabel,
  statusClass,
  statusLabel,
  typeLabel,
} from '../lib/monitorDisplay'

const PAGE_SIZE = 20
const POLL_INTERVAL_MS = 30_000

const monitors = ref<Monitor[]>([])
const page = ref(0)
const totalElements = ref(0)
const loading = ref(true)
const refreshing = ref(false)
const error = ref<string | null>(null)
let pollTimer: number | undefined

const totalPages = computed(() => Math.max(1, Math.ceil(totalElements.value / PAGE_SIZE)))
const hasPrevious = computed(() => page.value > 0)
const hasNext = computed(() => page.value + 1 < totalPages.value)

async function load(targetPage = page.value, initial = false): Promise<void> {
  if (refreshing.value) {
    return
  }
  refreshing.value = true
  if (initial) {
    loading.value = true
  }
  try {
    const result = await listMonitors(targetPage, PAGE_SIZE)
    monitors.value = result.items
    page.value = result.page
    totalElements.value = result.totalElements
    error.value = null
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载监控项失败'
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

function refresh(): void {
  void load(page.value)
}

function goToPage(target: number): void {
  if (target < 0 || target >= totalPages.value) {
    return
  }
  void load(target)
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') {
    refresh()
  }
}

onMounted(() => {
  void load(0, true)
  pollTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible') {
      refresh()
    }
  }, POLL_INTERVAL_MS)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
  }
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <h1>监控项</h1>
        <p class="muted">共 {{ totalElements }} 个监控项</p>
      </div>
      <button class="button" type="button" :disabled="refreshing" @click="refresh">
        {{ refreshing ? '刷新中…' : '刷新' }}
      </button>
    </header>

    <div v-if="error" class="error-panel">
      <span>{{ error }}</span>
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
          </tr>
        </tbody>
      </table>

      <nav class="pagination" aria-label="分页">
        <button
          class="button"
          type="button"
          :disabled="!hasPrevious || refreshing"
          @click="goToPage(page - 1)"
        >
          上一页
        </button>
        <span class="muted">第 {{ page + 1 }} / {{ totalPages }} 页</span>
        <button
          class="button"
          type="button"
          :disabled="!hasNext || refreshing"
          @click="goToPage(page + 1)"
        >
          下一页
        </button>
      </nav>
    </template>
  </section>
</template>
