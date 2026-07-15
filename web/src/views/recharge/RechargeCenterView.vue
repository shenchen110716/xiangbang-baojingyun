<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as rechargeApi from '@/api/recharge'
import { listEnterprises } from '@/api/enterprises'
import type { Enterprise, InsurerAccount, InsurerAccountLink, RechargeRequest } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { money, formatDateTime } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import StatTile from '@/components/StatTile.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()
const loading = ref(true)
const requests = ref<RechargeRequest[]>([])
const insurerAccountLinks = ref<InsurerAccountLink[]>([])
const insurerAccounts = ref<InsurerAccount[]>([])
const enterprises = ref<Enterprise[]>([])

async function load() {
  loading.value = true
  try {
    const tasks: Promise<unknown>[] = [
      rechargeApi.listRechargeRequests().then((r) => (requests.value = r)),
      rechargeApi.listInsurerAccountLinks().then((r) => (insurerAccountLinks.value = r)),
    ]
    if (auth.isAdmin()) {
      tasks.push(rechargeApi.listInsurerAccounts().then((r) => (insurerAccounts.value = r)))
      tasks.push(listEnterprises().then((r) => (enterprises.value = r)))
    }
    await Promise.all(tasks)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const { page, pageSize, total: pagedTotal, paged } = usePagedList(requests)
const pendingCount = computed(() => requests.value.filter((r) => r.status === 'pending').length)

const STATUS_TEXT: Record<string, string> = { pending: '待确认', confirmed: '已到账', rejected: '已驳回' }
const STATUS_TYPE: Record<string, string> = { pending: 'warning', confirmed: 'success', rejected: 'danger' }

// ---- submit ----
const submitVisible = ref(false)
const submitForm = reactive({ enterprise_id: null as number | null, account_type: 'premium' as 'premium' | 'usage', insurer: '', amount: 0, file: null as File | null })
const matchedAccount = computed(() => {
  if (submitForm.account_type !== 'premium' || !submitForm.insurer.trim()) return null
  const link = insurerAccountLinks.value.find((l) => l.insurer === submitForm.insurer.trim())
  return link ? insurerAccounts.value.find((a) => a.id === link.account_id) ?? null : null
})
function openSubmit() {
  Object.assign(submitForm, { enterprise_id: auth.isEnterprise() ? auth.user?.enterprise_id ?? null : null, account_type: 'premium', insurer: '', amount: 0, file: null })
  submitVisible.value = true
}
function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  submitForm.file = input.files?.[0] ?? null
}
async function submitRecharge() {
  if (!submitForm.enterprise_id) { ElMessage.error('请选择投保单位'); return }
  if (submitForm.account_type === 'premium' && !submitForm.insurer.trim()) { ElMessage.error('请填写保司名称'); return }
  if (submitForm.amount <= 0) { ElMessage.error('请输入充值金额'); return }
  if (!submitForm.file) { ElMessage.error('请上传转账回单'); return }
  try {
    await rechargeApi.createRechargeRequest({
      enterprise_id: submitForm.enterprise_id,
      account_type: submitForm.account_type,
      insurer: submitForm.insurer.trim(),
      amount: submitForm.amount,
      file: submitForm.file,
    })
    ElMessage.success('充值申请已提交，等待平台确认到账')
    submitVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- admin review ----
async function confirmRequest(row: RechargeRequest) {
  try {
    await ElMessageBox.confirm(`确认「${row.enterprise_name}」的这笔 ${money(row.amount)} 已经到账吗？`, '确认到账', { type: 'warning' })
  } catch { return }
  try {
    await rechargeApi.confirmRechargeRequest(row.id)
    ElMessage.success('已确认到账')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function rejectRequest(row: RechargeRequest) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回充值申请', { inputValidator: (v) => !!v?.trim() || '驳回原因必填' })
    await rechargeApi.rejectRechargeRequest(row.id, value)
    ElMessage.success('已驳回')
    load()
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message)
  }
}
</script>

<template>
  <div v-loading="loading" class="recharge-view">
    <div class="stat-grid">
      <StatTile label="待确认申请" :value="pendingCount" hint-type="warning" />
    </div>

    <PageCard title="充值记录" :count="requests.length">
      <template #actions>
        <el-button type="primary" @click="openSubmit">＋ 发起充值</el-button>
      </template>
      <el-table :data="paged" size="small">
        <el-table-column v-if="auth.isAdmin()" prop="enterprise_name" label="投保单位" min-width="140" />
        <el-table-column label="账户类型" width="100">
          <template #default="{ row }">{{ row.account_type === 'premium' ? '保费' : '使用费' }}</template>
        </el-table-column>
        <el-table-column prop="insurer" label="保司" min-width="120">
          <template #default="{ row }">{{ row.insurer || '—' }}</template>
        </el-table-column>
        <el-table-column label="金额" width="110">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="回单" width="90">
          <template #default="{ row }">
            <a v-if="row.receipt_download_url" :href="row.receipt_download_url" target="_blank" rel="noopener">查看</a>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TYPE[row.status]" size="small">{{ STATUS_TEXT[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="驳回原因" min-width="160">
          <template #default="{ row }">{{ row.reject_reason || '—' }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin()" label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'pending'">
              <el-button link type="primary" size="small" @click="confirmRequest(row)">确认到账</el-button>
              <el-button link type="danger" size="small" @click="rejectRequest(row)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <el-dialog v-model="submitVisible" title="发起充值" width="480px">
      <el-form :model="submitForm" label-width="100px">
        <el-form-item v-if="auth.isAdmin()" label="投保单位" required>
          <el-select v-model="submitForm.enterprise_id" filterable placeholder="请选择">
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="账户类型" required>
          <el-radio-group v-model="submitForm.account_type">
            <el-radio-button value="premium">保费</el-radio-button>
            <el-radio-button value="usage">使用费</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="submitForm.account_type === 'premium'" label="保司" required>
          <el-input v-model="submitForm.insurer" placeholder="请填写保险公司名称" />
        </el-form-item>
        <el-form-item v-if="matchedAccount" label="收款账户">
          <div class="account-hint">
            <p>{{ matchedAccount.bank_name }} · {{ matchedAccount.account_no }}</p>
            <p>户名：{{ matchedAccount.account_holder }}</p>
            <p v-if="matchedAccount.insurers.length > 1" class="muted">该账户同时用于：{{ matchedAccount.insurers.join('、') }}</p>
          </div>
        </el-form-item>
        <el-form-item label="充值金额" required><el-input-number v-model="submitForm.amount" :min="0.01" :step="100" style="width: 100%" /></el-form-item>
        <el-form-item label="转账回单" required><input type="file" accept=".pdf,.jpg,.jpeg,.png" @change="handleFileChange" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRecharge">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.recharge-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.account-hint {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
