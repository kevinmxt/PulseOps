<script setup lang="ts">
import type { ErrorDetails } from '../lib/apiError'

defineProps<{ details: ErrorDetails }>()
</script>

<template>
  <div class="error-panel server-error" role="alert">
    <div>
      <strong class="mono">{{ details.code }}</strong>
      <p>{{ details.message }}</p>
      <p v-if="details.traceId" class="mono muted">traceId: {{ details.traceId }}</p>
    </div>
  </div>
</template>
