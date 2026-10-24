<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { getMonitor, listChecks, listIncidents } from '../api/client'
import type { CheckResult, Incident, Monitor } from '../api/types'
import PaginationControls from '../components/PaginationControls.vue'
import ServerErrorPanel from '../components/ServerErrorPanel.vue'
import { toErrorDetails, type ErrorDetails } from '../lib/apiError'
import { formatAbsoluteTime, formatLatency, formatRelativeTime } from '../lib/format'
import {
  displayOrDash,
  incidentStatusClass,
  incidentStatusLabel,
} from '../lib/incidentDisplay'
import {
  checkResultClass,
  checkResultLabel,
  lastKnownStatusLabel,
  statusClass,
  typeLabel,
} from '../lib/monitorDisplay'

const CHECKS_PAGE_SIZE = 20
const OPEN_INCIDENT_LIMIT = 5

const props = defineProps<{ id: string }>()

const monitor = ref<Monitor | null>(null)
const loading = ref(true)
const error = ref<ErrorDetails | null>(null)

const checks = ref<CheckResult[]>([])
const checksPage = ref(0)
const checksTotal = ref(0)
const checksLoading = ref(false)
const checksError = ref<ErrorDetails | null>(null)

const incidents = ref<Incident[]>([])
const incidentsLoading = ref(false)
const incidentsError = ref<ErrorDetails | null>(null)

function monitorId(): number | null {
  const id = Number(props.id)
  return Number.isInteger(id) && id > 0 ? id : null
}

async function loadChecks(targetPage: number): Promise<void> {
  const id = monitorId()
  if (id === null) {
    return
  }
  checksLoading.value = true
  checksError.value = null
  try {
    const result = await listChecks(id, targetPage, CHECKS_PAGE_SIZE)
    checks.value = result.items
    checksPage.value = result.page
    checksTotal.value = result.totalElements
  } catch (cause) {
    checksError.value = toErrorDetails(cause)
  } finally {
    checksLoading.value = false
  }
}

async function loadOpenIncidents(): Promise<void> {
  const id = monitorId()
  if (id === null) {
    return
  }
  incidentsLoading.value = true
  incidentsError.value = null
  try {
    const result = await listIncidents({
      status: 'OPEN',
      monitorId: id,
      page: 0,
      size: OPEN_INCIDENT_LIMIT,
    })
    incidents.value = result.items
  } catch (cause) {
    incidentsError.value = toErrorDetails(cause)
  } finally {
    incidentsLoading.value = false
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  error.value = null
  monitor.value = null
  checks.value = []
  checksPage.value = 0
  checksTotal.value = 0
  checksError.value = null
  incidents.value = []
  incidentsError.value = null

  const id = monitorId()
  if (id === null) {
    error.value = {
      code: 'INVALID_MONITOR_ID',
      message: '监控项 ID 无效',
      traceId: null,
    }
    loading.value = false
    return
  }

  try {
    monitor.value = await getMonitor(id)
    await Promise.all([loadChecks(0), loadOpenIncidents()])
  } catch (cause) {
    error.value = toErrorDetails(cause)
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
watch(() => props.id, loadAll)
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <h1>{{ monitor?.name ?? '监控项详情' }}</h1>
        <p class="mono muted">Monitor #{{ id }}</p>
      </div>
      <div v-if="monitor" class="page-actions">
        <button class="button" type="button" :disabled="loading" @click="loadAll">刷新</button>
        <RouterLink class="button" :to="`/monitors/${id}/edit`">编辑</RouterLink>
        <RouterLink class="button" to="/monitors">返回列表</RouterLink>
      </div>
    </header>

    <p v-if="loading" class="muted">加载中…</p>

    <template v-else-if="error">
      <ServerErrorPanel :details="error" />
      <div class="page-actions">
        <button class="button" type="button" @click="loadAll">重试</button>
        <RouterLink class="button" to="/monitors">返回列表</RouterLink>
      </div>
    </template>

    <template v-else-if="monitor">
      <dl class="config-grid">
        <div class="config-item">
          <dt>类型</dt>
          <dd><span class="type-badge">{{ typeLabel(monitor.type) }}</span></dd>
        </div>
        <div class="config-item">
          <dt>启用状态</dt>
          <dd>{{ monitor.enabled ? '已启用' : '已禁用' }}</dd>
        </div>
        <div class="config-item">
          <dt>Monitor Status</dt>
          <dd>
            <span class="status-badge" :class="statusClass(monitor.currentStatus, true)">
              {{ lastKnownStatusLabel(monitor.currentStatus) }}
            </span>
            <span class="mono muted">（{{ monitor.currentStatus }}）</span>
          </dd>
        </div>
        <div class="config-item">
          <dt>最近检查</dt>
          <dd>
            <span :title="formatAbsoluteTime(monitor.lastCheckedAt)">
              {{ formatRelativeTime(monitor.lastCheckedAt) }}
            </span>
          </dd>
        </div>
        <div class="config-item">
          <dt>最近成功</dt>
          <dd>
            <span :title="formatAbsoluteTime(monitor.lastSuccessAt)">
              {{ formatRelativeTime(monitor.lastSuccessAt) }}
            </span>
          </dd>
        </div>

        <template v-if="monitor.type === 'HTTP'">
          <div class="config-item">
            <dt>Target URL</dt>
            <dd class="mono">{{ displayOrDash(monitor.targetUrl) }}</dd>
          </div>
          <div class="config-item">
            <dt>方法</dt>
            <dd>{{ displayOrDash(monitor.method) }}</dd>
          </div>
          <div class="config-item">
            <dt>检查间隔</dt>
            <dd>{{ monitor.intervalSeconds }} 秒</dd>
          </div>
          <div class="config-item">
            <dt>连接 / 响应超时</dt>
            <dd>{{ displayOrDash(monitor.connectTimeoutMs) }} / {{ displayOrDash(monitor.responseTimeoutMs) }} ms</dd>
          </div>
          <div class="config-item">
            <dt>期望状态码</dt>
            <dd>{{ displayOrDash(monitor.expectedStatusMin) }}–{{ displayOrDash(monitor.expectedStatusMax) }}</dd>
          </div>
          <div class="config-item">
            <dt>失败 / 恢复阈值</dt>
            <dd>{{ displayOrDash(monitor.failureThreshold) }} / {{ displayOrDash(monitor.recoveryThreshold) }}</dd>
          </div>
        </template>

        <template v-else>
          <div class="config-item">
            <dt>预期间隔</dt>
            <dd>{{ monitor.intervalSeconds }} 秒</dd>
          </div>
          <div class="config-item">
            <dt>宽限时间</dt>
            <dd>{{ displayOrDash(monitor.heartbeatGraceSeconds) }} 秒</dd>
          </div>
          <div class="config-item">
            <dt>最近心跳</dt>
            <dd>
              <span :title="formatAbsoluteTime(monitor.lastHeartbeatAt)">
                {{ formatRelativeTime(monitor.lastHeartbeatAt) }}
              </span>
            </dd>
          </div>
        </template>
      </dl>

      <p v-if="monitor.type === 'HEARTBEAT'" class="muted token-hint">
        令牌不会在详情页显示；需要重新获取时请在
        <RouterLink :to="`/monitors/${id}/edit`">编辑页</RouterLink>轮换令牌。
      </p>

      <section class="detail-section">
        <div class="section-header">
          <h2>检查历史</h2>
        </div>

        <p v-if="checksLoading" class="muted">加载中…</p>
        <template v-else-if="checksError">
          <ServerErrorPanel :details="checksError" />
          <button class="button" type="button" @click="loadChecks(checksPage)">重试</button>
        </template>
        <div v-else-if="checks.length === 0" class="empty-state">暂无检查记录</div>
        <template v-else>
          <table class="data-table">
            <thead>
              <tr>
                <th>开始时间</th>
                <th>结果</th>
                <th>失败类型</th>
                <th>HTTP 状态码</th>
                <th>耗时</th>
                <th>错误摘要</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="check in checks" :key="check.id">
                <td>{{ formatAbsoluteTime(check.startedAt) }}</td>
                <td>
                  <span class="status-badge" :class="checkResultClass(check.success)">
                    {{ checkResultLabel(check.success) }}
                  </span>
                </td>
                <td class="mono">{{ displayOrDash(check.failureType) }}</td>
                <td>{{ displayOrDash(check.httpStatusCode) }}</td>
                <td class="mono">{{ formatLatency(check.durationMs) }}</td>
                <td>{{ displayOrDash(check.errorSummary) }}</td>
              </tr>
            </tbody>
          </table>
          <PaginationControls
            :page="checksPage"
            :size="CHECKS_PAGE_SIZE"
            :total-elements="checksTotal"
            :disabled="checksLoading"
            @change="loadChecks"
          />
        </template>
      </section>

      <section class="detail-section">
        <div class="section-header">
          <h2>活动 Incident</h2>
          <RouterLink :to="`/incidents?monitor_id=${id}`">查看全部</RouterLink>
        </div>

        <p v-if="incidentsLoading" class="muted">加载中…</p>
        <template v-else-if="incidentsError">
          <ServerErrorPanel :details="incidentsError" />
          <button class="button" type="button" @click="loadOpenIncidents">重试</button>
        </template>
        <div v-else-if="incidents.length === 0" class="empty-state">
          当前没有活动 Incident
        </div>
        <table v-else class="data-table">
          <thead>
            <tr>
              <th>状态</th>
              <th>开始时间</th>
              <th>失败类型</th>
              <th>摘要</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="incident in incidents" :key="incident.id">
              <td>
                <span class="status-badge" :class="incidentStatusClass(incident.status)">
                  {{ incidentStatusLabel(incident.status) }}
                </span>
              </td>
              <td>{{ formatAbsoluteTime(incident.startedAt) }}</td>
              <td class="mono">{{ incident.failureType }}</td>
              <td>{{ incident.summary }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </section>
</template>
