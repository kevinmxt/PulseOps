<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getMonitor, updateMonitor } from '../api/client'
import type { Monitor } from '../api/types'
import MonitorForm from '../components/MonitorForm.vue'
import ServerErrorPanel from '../components/ServerErrorPanel.vue'
import { toErrorDetails, type ErrorDetails } from '../lib/apiError'
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

function monitorId(): number | null {
  const id = Number(props.id)
  return Number.isInteger(id) && id > 0 ? id : null
}

async function loadMonitor(): Promise<void> {
  loading.value = true
  monitor.value = null
  loadError.value = null
  serverError.value = null

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

    <div v-else-if="monitor && monitor.type === 'HEARTBEAT'" class="empty-state">
      <p>心跳监控项的编辑将在后续切片提供。</p>
      <RouterLink :to="`/monitors/${id}`">返回详情</RouterLink>
    </div>

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
