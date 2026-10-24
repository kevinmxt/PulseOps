<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/monitors">PulseOps</RouterLink>
      <nav class="app-nav">
        <RouterLink to="/monitors">监控项</RouterLink>
        <RouterLink to="/incidents">Incidents</RouterLink>
      </nav>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
