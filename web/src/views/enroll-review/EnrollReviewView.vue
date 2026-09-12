<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  exportEnrollSubmissions,
  listEnrollSubmissions,
  reviewEnrollSubmission,
  type EnrollSubmission,
} from '@/api/enrollReview'
import { useAuthStore } from '@/stores/auth'
import PageCard from '@/components/PageCard.vue'

const auth = useAuthStore()
const rows = ref<EnrollSubmission[]>([])
const loading = ref(true)
const statusFilter = ref('pending')
const pendingCount = computed(() => rows.value.filter((row) => row.review_status === 'pending').length)

async function load() {
  loading.value = true
  try {
    rows.value = await listEnrollSubmissions(statusFilter.value ? { review_status: statusFilter.value } : undefined)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function approve(row: EnrollSubmission) {
  try {
    await ElMessageBox.confirm(
      `通过后将为「${row.name}」创建正式参保记录（${row.enterprise_name} · ${row.position_name}）。`,
      '审核通过',
      { type: 'warning', confirmButtonText: '通过', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await reviewEnrollSubmission(row.id, { status: 'approved' })
    ElMessage.success('已通过并创建参保记录')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function reject(row: EnrollSubmission) {
  let note = ''
  try {
    const result = await ElMessageBox.prompt('请填写拒绝原因（会保留在记录里）', '拒绝提交', {
      confirmButtonText: '拒绝',
      cancelButtonText: '取消',
      inputPlaceholder: '如：姓名与身份证号不符',
    })
    note = result.value || ''
  } catch {
    return
  }
  try {
    await reviewEnrollSubmission(row.id, { status: 'rejected', review_note: note })
    ElMessage.success('已拒绝')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function doExport() {
  try {
    await exportEnrollSubmissions(statusFilter.value ? { review_status: statusFilter.value } : undefined)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="page">
    <PageCard title="扫码参保审核" :count="pendingCount" hint="员工扫码提交的参保信息，审核通过后才会创建正式参保记录；个人缴纳需支付完成才能通过">
      <template #actions>
        <el-radio-group v-model="statusFilter" size="small" @change="load">
          <el-radio-button value="pending">待审核</el-radio-button>
          <el-radio-button value="approved">已通过</el-radio-button>
          <el-radio-button value="rejected">已拒绝</el-radio-button>
          <el-radio-button value="">全部</el-radio-button>
        </el-radio-group>
        <el-button size="small" style="margin-left: 12px" @click="doExport">导出 Excel</el-button>
      </template>
      <el-table :data="rows" size="small" style="width: 100%">
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="id_number_masked" label="身份证号" min-width="150" />
        <el-table-column prop="phone" label="手机号" width="120" />
        <el-table-column label="缴费方式" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.payment_mode === 'personal' ? 'warning' : 'info'">{{ row.payment_mode_label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="支付状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.payment_mode === 'personal'" size="small" :type="row.payment_status === 'paid' ? 'success' : 'warning'">
              {{ row.payment_status_label }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="enterprise_name" label="投保单位" min-width="140" show-overflow-tooltip />
        <el-table-column prop="position_name" label="岗位" min-width="110" show-overflow-tooltip />
        <el-table-column label="审核状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.review_status === 'approved' ? 'success' : row.review_status === 'rejected' ? 'danger' : 'warning'">
              {{ row.review_status_label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="review_note" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column v-if="auth.isAdmin()" label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="row.review_status === 'pending'">
              <el-button size="small" type="primary" link @click="approve(row)">通过</el-button>
              <el-button size="small" type="danger" link @click="reject(row)">拒绝</el-button>
            </template>
            <span v-else class="muted">已处理</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无提交记录" />
    </PageCard>
  </div>
</template>

<style scoped>
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
