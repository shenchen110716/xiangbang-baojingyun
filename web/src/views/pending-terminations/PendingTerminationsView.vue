<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmPendingTermination, listPendingTerminations } from '@/api/pendingTerminations'
import type { PendingTermination } from '@/api/types'
import PageCard from '@/components/PageCard.vue'

const rows = ref<PendingTermination[]>([])
const loading = ref(true)

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
      `确认停保后，该账户名下（${row.affected_insurers}）当前 ${row.affected_count} 名在保人员将被立即停保，此操作不可撤销。`,
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
</script>

<template>
  <div v-loading="loading" class="page">
    <PageCard title="待处理停保" :count="rows.length" hint="保费账户余额耗尽后自动生成，需管理员确认后才会真正停保">
      <el-table :data="rows" size="small" style="width: 100%">
        <el-table-column prop="enterprise_id" label="企业 ID" width="90" />
        <el-table-column prop="affected_insurers" label="受影响保司" min-width="160" />
        <el-table-column prop="affected_count" label="受影响人数" width="100" />
        <el-table-column label="状态" width="180">
          <template #default="{ row }">{{ statusLabel[row.status as PendingTermination['status']] }}</template>
        </el-table-column>
        <el-table-column prop="created_at" label="生成时间" width="180" />
        <el-table-column label="操作" width="120">
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
</style>
