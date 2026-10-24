<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    page: number
    size: number
    totalElements: number
    disabled?: boolean
  }>(),
  {
    disabled: false,
  },
)

const emit = defineEmits<{
  change: [page: number]
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.totalElements / props.size)))

function go(target: number): void {
  if (target < 0 || target >= totalPages.value) {
    return
  }
  emit('change', target)
}
</script>

<template>
  <nav class="pagination" aria-label="分页">
    <button
      class="button"
      type="button"
      :disabled="disabled || page === 0"
      @click="go(page - 1)"
    >
      上一页
    </button>
    <span class="muted">第 {{ page + 1 }} / {{ totalPages }} 页</span>
    <button
      class="button"
      type="button"
      :disabled="disabled || page + 1 >= totalPages"
      @click="go(page + 1)"
    >
      下一页
    </button>
  </nav>
</template>
