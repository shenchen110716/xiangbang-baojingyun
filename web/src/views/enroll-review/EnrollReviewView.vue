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
        <!-- 列合并排版：姓名+身份证号+手机、缴费方式+支付状态、投保单位+岗位、
             审核状态+备注各折成一列，与参保员工列表（WorkersView）同一阅读结构。 -->
        <el-table-column label="被保险人" min-width="170">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <div class="muted">{{ row.id_number_masked }}</div>
            <div v-if="row.phone" class="muted">{{ row.phone }}</div>
          </template>
        </el-table-column>
        <!-- 支付状态只对个人缴纳有意义且是审核前提，保留彩色 tag 放在缴费方式下行，不降级成灰字 -->
        <el-table-column label="缴费方式/支付" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.payment_mode === 'personal' ? 'warning' : 'info'">{{ row.payment_mode_label }}</el-tag>
            <div v-if="row.payment_mode === 'personal'" class="pay-line">
              <el-tag size="small" :type="row.payment_status === 'paid' ? 'success' : 'warning'">
                {{ row.payment_status_label }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="投保单位/岗位" min-width="160">
          <template #default="{ row }">
            <div>{{ row.enterprise_name }}</div>
            <div class="muted">{{ row.position_name }}</div>
          </template>
        </el-table-column>
        <el-table-column label="审核状态/备注" min-width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.review_status === 'approved' ? 'success' : row.review_status === 'rejected' ? 'danger' : 'warning'">
              {{ row.review_status_label }}
            </el-tag>
            <div v-if="row.review_note" class="muted note-line" :title="row.review_note">{{ row.review_note }}</div>
          </template>
        </el-table-column>
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
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
/* 拒绝原因挤在状态标签下方，超长单行省略，完整内容靠 title 悬浮提示 */
.note-line {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 2px;
}
.pay-line {
  margin-top: 2px;
}
</style>
