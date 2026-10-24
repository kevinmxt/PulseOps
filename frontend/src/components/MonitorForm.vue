<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { ErrorDetails } from '../lib/apiError'
import ServerErrorPanel from './ServerErrorPanel.vue'
import {
  validateHttpMonitorForm,
  type HttpMonitorFormValues,
  type MonitorFormErrors,
} from '../lib/monitorInput'

const props = withDefaults(
  defineProps<{
    initial: HttpMonitorFormValues
    submitLabel: string
    submitting?: boolean
    serverError?: ErrorDetails | null
  }>(),
  {
    submitting: false,
    serverError: null,
  },
)

const emit = defineEmits<{
  submit: [values: HttpMonitorFormValues]
}>()

const values = reactive<HttpMonitorFormValues>({ ...props.initial })
const errors = ref<MonitorFormErrors>({})

function submit(): void {
  const result = validateHttpMonitorForm(values)
  errors.value = result
  if (Object.keys(result).length > 0) {
    return
  }
  emit('submit', { ...values })
}
</script>

<template>
  <form class="form-panel" novalidate @submit.prevent="submit">
    <ServerErrorPanel v-if="serverError" :details="serverError" />

    <div class="form-grid">
      <div class="form-field full">
        <label for="monitor-name">名称</label>
        <input id="monitor-name" v-model="values.name" type="text" autocomplete="off" />
        <p v-if="errors.name" class="field-error">{{ errors.name }}</p>
      </div>

      <div class="form-field full">
        <label for="monitor-url">Target URL</label>
        <input
          id="monitor-url"
          v-model="values.targetUrl"
          type="text"
          autocomplete="off"
          placeholder="https://example.com/health"
        />
        <p v-if="errors.targetUrl" class="field-error">{{ errors.targetUrl }}</p>
      </div>

      <div class="form-field">
        <label for="monitor-method">方法</label>
        <select id="monitor-method" v-model="values.method">
          <option value="GET">GET</option>
          <option value="HEAD">HEAD</option>
        </select>
      </div>

      <div class="form-field">
        <label for="monitor-interval">检查间隔（秒）</label>
        <input
          id="monitor-interval"
          v-model.number="values.intervalSeconds"
          type="number"
          min="10"
          max="86400"
        />
        <p v-if="errors.intervalSeconds" class="field-error">{{ errors.intervalSeconds }}</p>
      </div>

      <div class="form-field full form-checkbox">
        <input id="monitor-enabled" v-model="values.enabled" type="checkbox" />
        <label for="monitor-enabled">启用监控</label>
      </div>
    </div>

    <details class="advanced">
      <summary>高级设置</summary>
      <div class="form-grid advanced-grid">
        <div class="form-field">
          <label for="monitor-connect-timeout">连接超时（毫秒）</label>
          <input
            id="monitor-connect-timeout"
            v-model.number="values.connectTimeoutMs"
            type="number"
            min="1000"
            max="10000"
          />
          <p v-if="errors.connectTimeoutMs" class="field-error">{{ errors.connectTimeoutMs }}</p>
        </div>

        <div class="form-field">
          <label for="monitor-response-timeout">响应超时（毫秒）</label>
          <input
            id="monitor-response-timeout"
            v-model.number="values.responseTimeoutMs"
            type="number"
            min="1000"
            max="30000"
          />
          <p v-if="errors.responseTimeoutMs" class="field-error">
            {{ errors.responseTimeoutMs }}
          </p>
        </div>

        <div class="form-field">
          <label for="monitor-status-min">期望最小状态码</label>
          <input
            id="monitor-status-min"
            v-model.number="values.expectedStatusMin"
            type="number"
            min="100"
            max="599"
          />
          <p v-if="errors.expectedStatusMin" class="field-error">{{ errors.expectedStatusMin }}</p>
        </div>

        <div class="form-field">
          <label for="monitor-status-max">期望最大状态码</label>
          <input
            id="monitor-status-max"
            v-model.number="values.expectedStatusMax"
            type="number"
            min="100"
            max="599"
          />
          <p v-if="errors.expectedStatusMax" class="field-error">{{ errors.expectedStatusMax }}</p>
        </div>

        <div class="form-field">
          <label for="monitor-failure-threshold">失败阈值</label>
          <input
            id="monitor-failure-threshold"
            v-model.number="values.failureThreshold"
            type="number"
            min="1"
            max="10"
          />
          <p v-if="errors.failureThreshold" class="field-error">{{ errors.failureThreshold }}</p>
        </div>

        <div class="form-field">
          <label for="monitor-recovery-threshold">恢复阈值</label>
          <input
            id="monitor-recovery-threshold"
            v-model.number="values.recoveryThreshold"
            type="number"
            min="1"
            max="10"
          />
          <p v-if="errors.recoveryThreshold" class="field-error">{{ errors.recoveryThreshold }}</p>
        </div>
      </div>
    </details>

    <div class="form-actions">
      <button class="button button-primary" type="submit" :disabled="submitting">
        {{ submitting ? '保存中…' : submitLabel }}
      </button>
    </div>
  </form>
</template>
