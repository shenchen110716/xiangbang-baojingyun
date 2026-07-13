<script setup lang="ts">
const search = defineModel<string>('search', { default: '' })
defineProps<{ placeholder?: string }>()
const emit = defineEmits<{ search: [] }>()
</script>

<template>
  <div class="filter-bar">
    <el-input
      v-model="search"
      :placeholder="placeholder || '搜索'"
      clearable
      style="width: 280px"
      @keyup.enter="emit('search')"
      @clear="emit('search')"
    >
      <template #suffix>
        <el-icon @click="emit('search')"><Search /></el-icon>
      </template>
    </el-input>
    <slot />
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  align-items: center;
}
</style>
