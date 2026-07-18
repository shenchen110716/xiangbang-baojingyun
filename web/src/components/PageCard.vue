<script setup lang="ts">
// Replaces the legacy `.panel.table-panel` + `.table-caption` pattern that
// wraps nearly every list page (`<table-panel><table-caption>标题 <small>共 N 条</small></table-caption><table>...`).
defineProps<{
  title: string
  count?: number
  hint?: string
}>()
</script>

<template>
  <div class="page-card">
    <div class="card-caption">
      <div class="caption-left">
        <span class="title-bar" />
        <b>{{ title }}</b>
        <span v-if="count !== undefined" class="count-pill">{{ count }}</span>
      </div>
      <span v-if="hint" class="hint">{{ hint }}</span>
      <div class="actions"><slot name="actions" /></div>
    </div>
    <slot />
  </div>
</template>

<style scoped>
.page-card {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--app-radius-card);
  box-shadow: var(--app-shadow-card);
  overflow: hidden;
}
.card-caption {
  padding: 15px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.caption-left {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.title-bar {
  width: 3px;
  height: 14px;
  border-radius: 3px;
  background: var(--el-color-primary);
  flex: none;
}
.card-caption b {
  font-size: var(--app-fs-h2);
  font-weight: 650;
  color: var(--el-text-color-primary);
  letter-spacing: -0.01em;
}
.count-pill {
  font-size: var(--app-fs-micro);
  font-weight: 600;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  padding: 1px 8px;
  border-radius: 999px;
  line-height: 1.6;
}
.hint {
  color: var(--el-text-color-placeholder);
  font-size: var(--app-fs-caption);
  margin-left: auto;
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
