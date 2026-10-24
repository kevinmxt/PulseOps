<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createMonitor } from '../api/client'
import type { MonitorInput, MonitorType } from '../api/types'
import HeartbeatForm from '../components/HeartbeatForm.vue'
import HeartbeatTokenPanel from '../components/HeartbeatTokenPanel.vue'
import MonitorForm from '../components/MonitorForm.vue'
import { toErrorDetails, type ErrorDetails } from '../lib/apiError'
import {
  HEARTBEAT_MONITOR_DEFAULTS,
  heartbeatFormToMonitorInput,
  type HeartbeatFormValues,
} from '../lib/heartbeatInput'
import {
  HTTP_MONITOR_DEFAULTS,
  formValuesToMonitorInput,
  type HttpMonitorFormValues,
} from '../lib/monitorInput'

const router = useRouter()
const type = ref<MonitorType>('HTTP')
const submitting = ref(false)
const serverError = ref<ErrorDetails | null>(null)
const createdHeartbeatId = ref<number | null>(null)
const heartbeatToken = ref<string | null>(null)

watch(type, () => {
  serverError.value = null
})

async function create(input: MonitorInput, createdType: MonitorType): Promise<void> {
  submitting.value = true
  serverError.value = null
  try {
    const created = await createMonitor(input)
    if (createdType === 'HEARTBEAT' && created.heartbeatToken) {
      createdHeartbeatId.value = created.id
      heartbeatToken.value = created.heartbeatToken
      return
    }
    await router.push(`/monitors/${created.id}`)
  } catch (cause) {
    serverError.value = toErrorDetails(cause)
  } finally {
    submitting.value = false
  }
}

function submitHttp(values: HttpMonitorFormValues): void {
  void create(formValuesToMonitorInput(values, 'HTTP'), 'HTTP')
}

function submitHeartbeat(values: HeartbeatFormValues): void {
  void create(heartbeatFormToMonitorInput(values), 'HEARTBEAT')
}

function resetCreate(): void {
  createdHeartbeatId.value = null
  heartbeatToken.value = null
  type.value = 'HTTP'
  serverError.value = null
}
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <h1>创建监控项</h1>
        <p class="muted">HTTP 主动探测或等待外部任务上报的心跳监控。</p>
      </div>
    </header>

    <template v-if="createdHeartbeatId !== null">
      <HeartbeatTokenPanel
        v-if="heartbeatToken"
        :token="heartbeatToken"
        @done="heartbeatToken = null"
      />
      <div v-else class="empty-state">令牌已保存。若遗失，请在编辑页轮换。</div>

      <div class="page-actions">
        <RouterLink class="button button-primary" :to="`/monitors/${createdHeartbeatId}`">
          前往详情
        </RouterLink>
        <button class="button" type="button" @click="resetCreate">再创建一个</button>
      </div>
    </template>

    <template v-else>
      <fieldset class="type-selector">
        <legend>类型</legend>
        <label>
          <input v-model="type" type="radio" value="HTTP" :disabled="submitting" />
          HTTP
        </label>
        <label>
          <input v-model="type" type="radio" value="HEARTBEAT" :disabled="submitting" />
          Heartbeat
        </label>
      </fieldset>

      <MonitorForm
        v-if="type === 'HTTP'"
        :initial="HTTP_MONITOR_DEFAULTS"
        submit-label="创建监控项"
        :submitting="submitting"
        :server-error="serverError"
        @submit="submitHttp"
      />
      <HeartbeatForm
        v-else
        :initial="HEARTBEAT_MONITOR_DEFAULTS"
        submit-label="创建监控项"
        :submitting="submitting"
        :server-error="serverError"
        @submit="submitHeartbeat"
      />
    </template>
  </section>
</template>
