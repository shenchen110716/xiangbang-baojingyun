<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as rechargeApi from '@/api/recharge'
import { recognizeReceiptAmount } from '@/api/ocr'
import type { RechargePaymentAccount } from '@/api/recharge'
import { listEnterprises } from '@/api/enterprises'
import type { Enterprise, RechargeRequest } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { money, formatDateTime } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import StatTile from '@/components/StatTile.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()
const route = useRoute()
const loading = ref(true)
const requests = ref<RechargeRequest[]>([])
const enterprises = ref<Enterprise[]>([])
const paymentOptions = ref<rechargeApi.PremiumPaymentOption[]>([])

async function load() {
  loading.value = true
  try {
    const tasks: Promise<unknown>[] = [
      rechargeApi.listRechargeRequests().then((r) => (requests.value = r)),
      rechargeApi.getRechargePaymentOptions().then((r) => (paymentOptions.value = r)).catch(() => (paymentOptions.value = [])),
    ]
    if (auth.isAdmin()) {
      tasks.push(listEnterprises().then((r) => (enterprises.value = r)))
    }
    await Promise.all(tasks)
  } finally {
    loading.value = false
  }
}
// Deep-link from a dashboard balance / alert: open the recharge dialog already
// pointed at the enterprise and account that needs topping up, so the click
// lands on the exact thing to fix instead of a blank form.
onMounted(async () => {
  await load()
  const q = route.query
  if (q.enterprise_id || q.account_type) {
    openSubmit({
      enterprise_id: q.enterprise_id ? Number(q.enterprise_id) : undefined,
      account_type: q.account_type === 'usage' ? 'usage' : q.account_type === 'premium' ? 'premium' : undefined,
      insurer: typeof q.insurer === 'string' ? q.insurer : undefined,
    })
  }
})

const { page, pageSize, total: pagedTotal, paged } = usePagedList(requests)
const pendingCount = computed(() => requests.value.filter((r) => r.status === 'pending').length)

const STATUS_TEXT: Record<string, string> = { pending: '待确认', confirmed: '已到账', rejected: '已驳回' }
const STATUS_TYPE: Record<string, string> = { pending: 'warning', confirmed: 'success', rejected: 'danger' }

// ---- submit ----
const submitVisible = ref(false)
const submitForm = reactive({ enterprise_id: null as number | null, account_type: 'premium' as 'premium' | 'usage', insurer: '', amount: 0, file: null as File | null })

// 收款账户（往哪里转账）由后端按账户类型解析——保费按保司、使用费按平台使用费
// 账户，企业端也可读。选择变化时实时拉取，让用户下单前就看到打款目标账户。
const paymentAccount = ref<RechargePaymentAccount | null>(null)
const paymentLoading = ref(false)
async function refreshPaymentAccount() {
  if (submitForm.account_type === 'premium' && !submitForm.insurer.trim()) {
    paymentAccount.value = null
    return
  }
  paymentLoading.value = true
  try {
    paymentAccount.value = await rechargeApi.getRechargePaymentAccount(submitForm.account_type, submitForm.insurer.trim())
  } catch {
    paymentAccount.value = null
  } finally {
    paymentLoading.value = false
  }
}
watch(() => [submitForm.account_type, submitForm.insurer], refreshPaymentAccount)
function openSubmit(prefill?: { enterprise_id?: number; account_type?: 'premium' | 'usage'; insurer?: string }) {
  Object.assign(submitForm, {
    enterprise_id: prefill?.enterprise_id ?? (auth.isEnterprise() ? auth.user?.enterprise_id ?? null : null),
    account_type: prefill?.account_type ?? 'premium',
    insurer: prefill?.insurer ?? '',
    amount: 0,
    file: null,
  })
  submitVisible.value = true
  refreshPaymentAccount()
}
const ocrHint = ref('')
const ocrLoading = ref(false)
async function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  submitForm.file = file
  ocrHint.value = ''
  // 图片回单尝试 OCR 自动带出金额（识别为便利功能，失败/未启用则静默，不影响手工填写）
  if (file && file.type.startsWith('image/')) {
    ocrLoading.value = true
    try {
      const res = await recognizeReceiptAmount(file)
      if (res.amount > 0) {
        submitForm.amount = res.amount
        ocrHint.value = res.mock ? `已识别金额 ${money(res.amount)}（模拟，请核对）` : `已识别金额 ${money(res.amount)}，请核对`
      }
    } catch {
      /* OCR 未启用或识别失败：静默，用户手工填写 */
    } finally {
      ocrLoading.value = false
    }
  }
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
        <el-button type="primary" @click="openSubmit()">＋ 发起充值</el-button>
      </template>
      <el-table :data="paged" size="small">
        <el-table-column v-if="auth.isAdmin()" prop="enterprise_name" label="投保单位" min-width="140" />
        <el-table-column label="账户类型" width="100">
          <template #default="{ row }">{{ row.account_type === 'premium' ? '保费' : '系统服务费' }}</template>
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
            <el-radio-button value="usage">系统服务费</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="submitForm.account_type === 'premium'" label="保司" required>
          <el-select v-model="submitForm.insurer" filterable allow-create default-first-option placeholder="请选择保司（已配置收款账户，也可直接输入）" style="width: 100%">
            <el-option v-for="opt in paymentOptions" :key="opt.insurer" :label="opt.insurer" :value="opt.insurer" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="paymentAccount" label="收款账户">
          <div class="account-hint">
            <p><b>{{ paymentAccount.account_holder }}</b></p>
            <p>{{ paymentAccount.bank_name }} · {{ paymentAccount.account_no }}</p>
            <p v-if="paymentAccount.insurers.length > 1" class="muted">该账户同时用于：{{ paymentAccount.insurers.join('、') }}</p>
            <p class="muted">请按此收款账户转账后上传回单</p>
          </div>
        </el-form-item>
        <el-form-item v-else-if="!paymentLoading && (submitForm.account_type === 'usage' || submitForm.insurer.trim())" label="收款账户">
          <span class="muted">平台尚未配置该账户的收款信息，请联系平台后再转账。</span>
        </el-form-item>
        <el-form-item label="充值金额" required><el-input-number v-model="submitForm.amount" :min="0.01" :step="100" style="width: 100%" /></el-form-item>
        <el-form-item label="转账回单" required>
          <div style="width: 100%">
            <input type="file" accept=".pdf,.jpg,.jpeg,.png" @change="handleFileChange" />
            <div v-if="ocrLoading" class="muted" style="font-size: 12px; margin-top: 4px">正在识别金额…</div>
            <div v-else-if="ocrHint" style="font-size: 12px; margin-top: 4px; color: var(--el-color-success)">{{ ocrHint }}</div>
            <div v-else class="muted" style="font-size: 12px; margin-top: 4px">上传图片回单可自动识别金额（需在系统设置开启 OCR）</div>
          </div>
        </el-form-item>
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
