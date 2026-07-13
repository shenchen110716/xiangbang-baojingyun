<script setup lang="ts">
// Replaces the legacy `detail(title, body)` helper — a generic read-only
// info popup used ~17 times across the old script.js for things like
// pricing breakdowns and product relation summaries.
defineProps<{ title: string }>()
const visible = defineModel<boolean>({ default: false })
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="480px" append-to-body destroy-on-close>
    <div class="detail-content">
      <slot />
    </div>
    <template #footer>
      <el-button type="primary" style="width: 100%" @click="visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.detail-content :deep(p) {
  margin: 6px 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
</style>
