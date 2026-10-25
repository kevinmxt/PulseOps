<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/client'
import { changePassword } from '../auth/session'
import ServerErrorPanel from '../components/ServerErrorPanel.vue'
import { toErrorDetails, type ErrorDetails } from '../lib/apiError'

const MIN_PASSWORD_LENGTH = 12
const MAX_PASSWORD_LENGTH = 128
const passwordRuleHint = `${MIN_PASSWORD_LENGTH}–${MAX_PASSWORD_LENGTH} 个字符`

const router = useRouter()
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const formError = ref<string | null>(null)
const serverError = ref<ErrorDetails | null>(null)
const submitting = ref(false)

function validate(): string | null {
  if (!currentPassword.value) {
    return '请输入当前密码'
  }
  if (
    newPassword.value.length < MIN_PASSWORD_LENGTH ||
    newPassword.value.length > MAX_PASSWORD_LENGTH
  ) {
    return `新密码长度需为 ${passwordRuleHint}`
  }
  if (newPassword.value === currentPassword.value) {
    return '新密码不能与当前密码相同'
  }
  if (confirmPassword.value !== newPassword.value) {
    return '两次输入的新密码不一致'
  }
  return null
}

async function submit(): Promise<void> {
  serverError.value = null
  const validation = validate()
  formError.value = validation
  if (validation) {
    return
  }

  submitting.value = true
  try {
    await changePassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
    })
    await router.replace({ path: '/login', query: { changed: '1' } })
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 'INVALID_CURRENT_PASSWORD') {
      formError.value = '当前密码不正确'
    } else {
      serverError.value = toErrorDetails(cause)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <h1>设置</h1>
        <p class="muted">修改管理员密码后，所有会话会失效，需要重新登录。</p>
      </div>
    </header>

    <form class="form-panel auth-form" @submit.prevent="submit">
      <div class="form-field">
        <label for="current-password">当前密码</label>
        <input
          id="current-password"
          v-model="currentPassword"
          type="password"
          autocomplete="current-password"
          :disabled="submitting"
        />
      </div>
      <div class="form-field">
        <label for="new-password">新密码</label>
        <input
          id="new-password"
          v-model="newPassword"
          type="password"
          autocomplete="new-password"
          :disabled="submitting"
        />
        <span class="field-hint">{{ passwordRuleHint }}</span>
      </div>
      <div class="form-field">
        <label for="confirm-password">确认新密码</label>
        <input
          id="confirm-password"
          v-model="confirmPassword"
          type="password"
          autocomplete="new-password"
          :disabled="submitting"
        />
      </div>

      <p v-if="formError" class="field-error" role="alert">{{ formError }}</p>
      <ServerErrorPanel v-if="serverError" :details="serverError" />

      <div class="form-actions">
        <button class="button button-primary" type="submit" :disabled="submitting">
          {{ submitting ? '保存中…' : '修改密码' }}
        </button>
      </div>
    </form>
  </section>
</template>
