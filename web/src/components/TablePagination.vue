<script setup lang="ts">
const page = defineModel<number>('page', { required: true })
const pageSize = defineModel<number>('pageSize', { required: true })
defineProps<{ total: number }>()
</script>

<template>
  <div class="table-pagination">
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      background
    />
  </div>
</template>

<style scoped>
.table-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 14px 20px;
}
</style>
