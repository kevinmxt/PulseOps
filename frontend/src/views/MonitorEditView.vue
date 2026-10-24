<script setup lang="ts">
defineProps<{ id: string }>()
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <h1>编辑监控项</h1>
        <p class="mono muted">Monitor #{{ id }}</p>
      </div>
    </header>
    <div class="empty-state">编辑表单将在后续切片中接入。</div>
  </section>
</template>
