<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    message: string
    confirmLabel?: string
    busy?: boolean
  }>(),
  {
    confirmLabel: '确认',
    busy: false,
  },
)

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

const dialog = ref<HTMLElement | null>(null)

function cancel(): void {
  if (!props.busy) {
    emit('cancel')
  }
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      await nextTick()
      dialog.value?.focus()
    }
  },
)
</script>

<template>
  <div v-if="open" class="dialog-backdrop" @click.self="cancel">
    <div
      ref="dialog"
      class="dialog"
      role="dialog"
      aria-modal="true"
      :aria-label="title"
      tabindex="-1"
      @keydown.esc="cancel"
    >
      <h2>{{ title }}</h2>
      <p class="muted">{{ message }}</p>
      <div class="dialog-actions">
        <button class="button" type="button" :disabled="busy" @click="cancel">取消</button>
        <button class="button button-danger" type="button" :disabled="busy" @click="emit('confirm')">
          {{ busy ? '处理中…' : confirmLabel }}
        </button>
      </div>
    </div>
  </div>
</template>
