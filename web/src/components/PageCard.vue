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
      <div>
        <b>{{ title }}</b>
        <small v-if="count !== undefined"> 共 {{ count }} 条记录</small>
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
  border-radius: 9px;
  overflow: hidden;
}
.card-caption {
  padding: 18px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-caption b {
  font-size: 14px;
}
.card-caption small {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  margin-left: 6px;
}
.hint {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}
</style>
