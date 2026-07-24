<script setup lang="ts">
import { ref } from 'vue'
import WorkersView from './WorkersView.vue'
import EnrollmentCenterView from '@/views/enrollment/EnrollmentCenterView.vue'

// 「参保员工管理」和「参停保中心」原来是两个菜单项，操作粒度不同（单个/勾选员工
// vs 按保司产品方案批量导出+发邮件），不适合把按钮硬塞进同一张表格；改成一个页面
// 里的两个 Tab，减少来回跳转菜单，同时保留各自完整、经过验证的操作逻辑。
const activeTab = ref('workers')
</script>

<template>
  <el-tabs v-model="activeTab" class="work-enrollment-tabs">
    <el-tab-pane label="员工列表" name="workers">
      <WorkersView />
    </el-tab-pane>
    <el-tab-pane label="批量参停保与保司邮件" name="enrollment" lazy>
      <EnrollmentCenterView />
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.work-enrollment-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}
.work-enrollment-tabs :deep(.el-tabs__item) {
  font-size: 15px;
  font-weight: 600;
}
</style>
