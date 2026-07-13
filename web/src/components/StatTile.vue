<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  hint?: string
  hintType?: 'success' | 'warning' | 'danger' | 'info'
}>()
</script>

<template>
  <div class="stat-tile">
    <div class="stat-label">{{ label }}</div>
    <b class="stat-value">{{ value }}</b>
    <small v-if="hint" :class="['stat-hint', hintType]">{{ hint }}</small>
  </div>
</template>

<style scoped>
.stat-tile {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 9px;
  padding: 18px 18px 14px;
  min-height: 100px;
}
.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.stat-value {
  display: block;
  font-size: 26px;
  margin: 12px 0 6px;
  letter-spacing: -0.02em;
}
.stat-hint {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.stat-hint.success {
  color: var(--el-color-success);
}
.stat-hint.warning {
  color: var(--el-color-warning);
}
.stat-hint.danger {
  color: var(--el-color-danger);
}
</style>
