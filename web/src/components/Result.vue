<script setup lang="ts">
// 统一的「加载中 / 出错 / 结果」显示。三种状态在 15 个页面里各写一遍必然写歪。
defineProps<{ loading?: boolean; error?: string; data?: unknown; okText?: string }>()
</script>

<template>
  <div v-if="loading" class="msg info">请求中…</div>
  <div v-else-if="error" class="msg bad">{{ error }}</div>
  <template v-else-if="data !== null && data !== undefined">
    <div v-if="okText" class="msg ok">{{ okText }}</div>
    <pre v-if="data !== ''" class="out">{{ JSON.stringify(data, null, 2) }}</pre>
  </template>
</template>
