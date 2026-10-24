<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createMonitor } from '../api/client'
import MonitorForm from '../components/MonitorForm.vue'
import { toErrorDetails, type ErrorDetails } from '../lib/apiError'
import {
  HTTP_MONITOR_DEFAULTS,
  formValuesToMonitorInput,
  type HttpMonitorFormValues,
} from '../lib/monitorInput'

const router = useRouter()
const submitting = ref(false)
const serverError = ref<ErrorDetails | null>(null)

async function submit(values: HttpMonitorFormValues): Promise<void> {
  submitting.value = true
  serverError.value = null
  try {
    const created = await createMonitor(formValuesToMonitorInput(values))
    await router.push(`/monitors/${created.id}`)
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
        <h1>创建监控项</h1>
        <p class="muted">目前支持 HTTP Monitor。</p>
      </div>
    </header>
    <MonitorForm
      :initial="HTTP_MONITOR_DEFAULTS"
      submit-label="创建监控项"
      :submitting="submitting"
      :server-error="serverError"
      @submit="submit"
    />
  </section>
</template>
