<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmPendingTermination, listPendingTerminations } from '@/api/pendingTerminations'
import type { PendingTermination } from '@/api/types'
import PageCard from '@/components/PageCard.vue'

const rows = ref<PendingTermination[]>([])
const loading = ref(true)
const pendingCount = computed(() => rows.value.filter((row) => row.status === 'pending').length)

async function load() {
  loading.value = true
  try {
    rows.value = await listPendingTerminations()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function confirm(row: PendingTermination) {
  try {
    await ElMessageBox.confirm(
      `确认停保后，该账户名下（${row.affected_insurers}）当前 ${row.current_affected_count} 名在保人员将被立即停保，此操作不可撤销。`,
      '确认停保',
      { type: 'warning', confirmButtonText: '确认停保', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const result = await confirmPendingTermination(row.id)
    ElMessage.success(`已停保 ${result.terminated_count} 人`)
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

const statusLabel: Record<PendingTermination['status'], string> = {
  pending: '待确认',
  confirmed: '已停保',
  dismissed: '已自动撤销（已充值）',
}
// 纯展示映射：状态由纯文本改为标签后需要配色，不涉及业务逻辑
const statusTagType: Record<PendingTermination['status'], 'warning' | 'info' | 'success'> = {
  pending: 'warning',
  confirmed: 'info',
  dismissed: 'success',
}
</script>

<template>
  <div v-loading="loading" class="page">
    <PageCard title="待处理停保" :count="pendingCount" hint="保费账户余额耗尽后自动生成，需管理员确认后才会真正停保">
      <el-table :data="rows" size="small" style="width: 100%">
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="affected-list">
              <b>当前受影响人员：</b>
              <template v-if="row.affected_people.length">
                <el-tag v-for="person in row.affected_people" :key="person.id" size="small">{{ person.name }}</el-tag>
              </template>
              <span v-else class="muted">当前无待停保人员或任务已结束</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="企业 / 保费账户" min-width="170">
          <template #default="{ row }">
            <div>{{ row.enterprise_name }}</div>
            <div class="muted">{{ row.account_label }}</div>
          </template>
        </el-table-column>
        <el-table-column label="受影响保司 / 人数" min-width="180">
          <template #default="{ row }">
            <div>{{ row.affected_insurers }}</div>
            <div class="muted">受影响 {{ row.status === 'pending' ? row.current_affected_count : row.affected_count }} 人</div>
          </template>
        </el-table-column>
        <el-table-column label="状态 / 生成时间" width="190">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType[row.status as PendingTermination['status']]">{{ statusLabel[row.status as PendingTermination['status']] }}</el-tag>
            <div class="muted">生成 {{ row.created_at }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="row.status === 'pending'" link type="danger" size="small" @click="confirm(row)">确认停保</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无待处理停保任务" :image-size="60" />
    </PageCard>
  </div>
</template>

<style scoped>
.page {
  padding: 20px 24px;
}

.affected-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 24px;
}

.muted {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
</style>
