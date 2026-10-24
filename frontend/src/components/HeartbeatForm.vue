<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { ErrorDetails } from '../lib/apiError'
import {
  validateHeartbeatForm,
  type HeartbeatFormErrors,
  type HeartbeatFormValues,
} from '../lib/heartbeatInput'
import ServerErrorPanel from './ServerErrorPanel.vue'

const props = withDefaults(
  defineProps<{
    initial: HeartbeatFormValues
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
  submit: [values: HeartbeatFormValues]
}>()

const values = reactive<HeartbeatFormValues>({ ...props.initial })
const errors = ref<HeartbeatFormErrors>({})

function submit(): void {
  const result = validateHeartbeatForm(values)
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
        <label for="heartbeat-name">名称</label>
        <input id="heartbeat-name" v-model="values.name" type="text" autocomplete="off" />
        <p v-if="errors.name" class="field-error">{{ errors.name }}</p>
      </div>

      <div class="form-field">
        <label for="heartbeat-interval">预期间隔（秒）</label>
        <input
          id="heartbeat-interval"
          v-model.number="values.intervalSeconds"
          type="number"
          min="30"
          max="2592000"
        />
        <p v-if="errors.intervalSeconds" class="field-error">{{ errors.intervalSeconds }}</p>
      </div>

      <div class="form-field">
        <label for="heartbeat-grace">宽限时间（秒）</label>
        <input
          id="heartbeat-grace"
          v-model.number="values.heartbeatGraceSeconds"
          type="number"
          min="0"
          max="86400"
        />
        <p v-if="errors.heartbeatGraceSeconds" class="field-error">
          {{ errors.heartbeatGraceSeconds }}
        </p>
      </div>

      <div class="form-field full form-checkbox">
        <input id="heartbeat-enabled" v-model="values.enabled" type="checkbox" />
        <label for="heartbeat-enabled">启用监控</label>
      </div>
    </div>

    <div class="form-actions">
      <button class="button button-primary" type="submit" :disabled="submitting">
        {{ submitting ? '保存中…' : submitLabel }}
      </button>
    </div>
  </form>
</template>
