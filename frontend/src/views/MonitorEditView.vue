<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  getMonitor,
  revokeHeartbeatToken,
  rotateHeartbeatToken,
  updateMonitor,
} from '../api/client'
import type { Monitor } from '../api/types'
import HeartbeatForm from '../components/HeartbeatForm.vue'
import HeartbeatTokenPanel from '../components/HeartbeatTokenPanel.vue'
import MonitorForm from '../components/MonitorForm.vue'
import ServerErrorPanel from '../components/ServerErrorPanel.vue'
import { toErrorDetails, type ErrorDetails } from '../lib/apiError'
import {
  heartbeatFormToMonitorInput,
  monitorToHeartbeatFormValues,
  type HeartbeatFormValues,
} from '../lib/heartbeatInput'
import {
  formValuesToMonitorInput,
  monitorToFormValues,
  type HttpMonitorFormValues,
} from '../lib/monitorInput'

const props = defineProps<{ id: string }>()
const router = useRouter()

const monitor = ref<Monitor | null>(null)
const loading = ref(true)
const loadError = ref<ErrorDetails | null>(null)
const submitting = ref(false)
const serverError = ref<ErrorDetails | null>(null)
const savedMessage = ref<string | null>(null)
const heartbeatToken = ref<string | null>(null)
const tokenBusy = ref(false)
const tokenError = ref<ErrorDetails | null>(null)
const tokenMessage = ref<string | null>(null)
const formVersion = ref(0)

function monitorId(): number | null {
  const id = Number(props.id)
  return Number.isInteger(id) && id > 0 ? id : null
}

async function loadMonitor(): Promise<void> {
  loading.value = true
  monitor.value = null
  loadError.value = null
  serverError.value = null
  savedMessage.value = null
  heartbeatToken.value = null
  tokenError.value = null
  tokenMessage.value = null

  const id = monitorId()
  if (id === null) {
    loadError.value = {
      code: 'INVALID_MONITOR_ID',
      message: '监控项 ID 无效',
      traceId: null,
    }
    loading.value = false
    return
  }
  try {
    monitor.value = await getMonitor(id)
  } catch (cause) {
    loadError.value = toErrorDetails(cause)
  } finally {
    loading.value = false
  }
}

onMounted(loadMonitor)
watch(() => props.id, loadMonitor)

async function submit(values: HttpMonitorFormValues): Promise<void> {
  const id = monitorId()
  const current = monitor.value
  if (id === null || current === null) {
    return
  }
  submitting.value = true
  serverError.value = null
  try {
    const updated = await updateMonitor(id, formValuesToMonitorInput(values, current.type))
    await router.push(`/monitors/${updated.id}`)
  } catch (cause) {
    serverError.value = toErrorDetails(cause)
  } finally {
    submitting.value = false
  }
}

async function submitHeartbeat(values: HeartbeatFormValues): Promise<void> {
  const id = monitorId()
  if (id === null) {
    return
  }
  submitting.value = true
  serverError.value = null
  savedMessage.value = null
  try {
    monitor.value = await updateMonitor(id, heartbeatFormToMonitorInput(values))
    formVersion.value += 1
    savedMessage.value = '已保存'
  } catch (cause) {
    serverError.value = toErrorDetails(cause)
  } finally {
    submitting.value = false
  }
}

async function rotateToken(): Promise<void> {
  const id = monitorId()
  if (id === null) {
    return
  }
  tokenBusy.value = true
  tokenError.value = null
  tokenMessage.value = null
  try {
    const response = await rotateHeartbeatToken(id)
    heartbeatToken.value = response.heartbeatToken
  } catch (cause) {
    tokenError.value = toErrorDetails(cause)
  } finally {
    tokenBusy.value = false
  }
}

async function revokeToken(): Promise<void> {
  const id = monitorId()
  if (id === null) {
    return
  }
  tokenBusy.value = true
  tokenError.value = null
  tokenMessage.value = null
  try {
    await revokeHeartbeatToken(id)
    heartbeatToken.value = null
    tokenMessage.value = '令牌已撤销，上报将返回 404。'
  } catch (cause) {
    tokenError.value = toErrorDetails(cause)
  } finally {
    tokenBusy.value = false
  }
}
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <h1>编辑监控项</h1>
        <p class="mono muted">Monitor #{{ id }}</p>
      </div>
    </header>

    <p v-if="loading" class="muted">加载中…</p>

    <template v-else-if="loadError">
      <ServerErrorPanel :details="loadError" />
      <RouterLink to="/monitors">返回监控项</RouterLink>
    </template>

    <template v-else-if="monitor && monitor.type === 'HEARTBEAT'">
      <HeartbeatForm
        :key="formVersion"
        :initial="monitorToHeartbeatFormValues(monitor)"
        submit-label="保存修改"
        :submitting="submitting"
        :server-error="serverError"
        @submit="submitHeartbeat"
      />
      <p v-if="savedMessage" class="muted">{{ savedMessage }}</p>

      <section class="token-section">
        <h2>心跳令牌</h2>
        <p class="muted">令牌只在创建或轮换时显示一次；遗失后必须轮换。</p>
        <div class="page-actions">
          <button class="button" type="button" :disabled="tokenBusy" @click="rotateToken">
            轮换令牌
          </button>
          <button
            class="button button-danger-outline"
            type="button"
            :disabled="tokenBusy"
            @click="revokeToken"
          >
            撤销令牌
          </button>
        </div>
        <ServerErrorPanel v-if="tokenError" :details="tokenError" />
        <p v-if="tokenMessage" class="muted">{{ tokenMessage }}</p>
        <HeartbeatTokenPanel
          v-if="heartbeatToken"
          :token="heartbeatToken"
          :show-title="false"
          @done="heartbeatToken = null"
        />
      </section>

      <p><RouterLink :to="`/monitors/${id}`">返回详情</RouterLink></p>
    </template>

    <MonitorForm
      v-else-if="monitor"
      :initial="monitorToFormValues(monitor)"
      submit-label="保存修改"
      :submitting="submitting"
      :server-error="serverError"
      @submit="submit"
    />
  </section>
</template>
