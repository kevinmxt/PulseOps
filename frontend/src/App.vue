<script setup lang="ts">
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { authState, logout } from './auth/session'

const router = useRouter()

async function handleLogout(): Promise<void> {
  try {
    await logout()
  } catch {
    // Even when the request fails, the local session is cleared.
  }
  await router.replace('/login')
}
</script>

<template>
  <div class="app-shell">
    <header v-if="authState.authenticated" class="app-header">
      <RouterLink class="brand" to="/monitors">PulseOps</RouterLink>
      <nav class="app-nav">
        <RouterLink to="/monitors">监控项</RouterLink>
        <RouterLink to="/incidents">Incidents</RouterLink>
      </nav>
      <div class="app-user">
        <span class="inline-label">{{ authState.username }}</span>
        <RouterLink to="/settings">设置</RouterLink>
        <button class="button" type="button" @click="handleLogout">登出</button>
      </div>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
