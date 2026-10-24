<script setup lang="ts">
import { computed, ref } from 'vue'
import { buildHeartbeatCommand } from '../lib/heartbeatCommand'

const props = defineProps<{ token: string }>()
const emit = defineEmits<{ done: [] }>()

const command = computed(() => buildHeartbeatCommand(window.location.origin, props.token))
const copied = ref<'token' | 'command' | null>(null)
const copyFailed = ref(false)

async function copy(value: string, kind: 'token' | 'command'): Promise<void> {
  copied.value = null
  copyFailed.value = false
  try {
    if (!navigator.clipboard?.writeText) {
      throw new Error('clipboard unavailable')
    }
    await navigator.clipboard.writeText(value)
    copied.value = kind
  } catch {
    copyFailed.value = true
  }
}
</script>

<template>
  <section class="token-panel">
    <h2>心跳令牌</h2>
    <p class="token-warning">令牌只显示一次，请立即保存。遗失后必须轮换令牌。</p>

    <div class="form-field">
      <label for="heartbeat-token">令牌</label>
      <div class="token-row">
        <input id="heartbeat-token" class="mono" :value="token" readonly />
        <button class="button" type="button" @click="copy(token, 'token')">复制令牌</button>
      </div>
    </div>

    <div class="form-field">
      <label for="heartbeat-command">上报命令</label>
      <div class="token-row">
        <input id="heartbeat-command" class="mono" :value="command" readonly />
        <button class="button" type="button" @click="copy(command, 'command')">复制命令</button>
      </div>
      <p class="field-hint">生产环境请将主机名替换为实际部署域名。</p>
    </div>

    <p v-if="copied" class="muted">已复制{{ copied === 'token' ? '令牌' : '命令' }}</p>
    <p v-if="copyFailed" class="field-error">复制失败，请手动选择文本。</p>

    <div class="form-actions">
      <button class="button button-primary" type="button" @click="emit('done')">我已保存</button>
    </div>
  </section>
</template>
