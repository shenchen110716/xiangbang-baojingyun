<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as financeApi from '@/api/finance'
import { INVOICE_STATUS_TEXT } from '@/api/finance'
import { getBilling } from '@/api/reports'
import { listEnterprises, getEnterpriseLedger, rechargeEnterprise } from '@/api/enterprises'
import type { BillingRow, Enterprise, Invoice, LedgerResponse } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { money, formatDateTime } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import StatTile from '@/components/StatTile.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()
const loading = ref(true)
const accounts = ref<BillingRow[]>([])
const invoices = ref<Invoice[]>([])
const enterprises = ref<Enterprise[]>([])

async function load() {
  loading.value = true
  try {
    const [accountRows, invoiceRows, enterpriseRows] = await Promise.all([getBilling(), financeApi.listInvoices(), listEnterprises()])
    accounts.value = accountRows
    invoices.value = invoiceRows
    enterprises.value = enterpriseRows
  } finally {
    loading.value = false
  }
}
onMounted(load)

const totalPremium = computed(() => accounts.value.filter((x) => x.account_type === 'premium').reduce((s, x) => s + x.balance, 0))
const totalUsage = computed(() => accounts.value.filter((x) => x.account === '平台使用费账户').reduce((s, x) => s + x.balance, 0))
const monthUsageAccrued = computed(() => accounts.value.filter((x) => x.account === '平台使用费账户').reduce((s, x) => s + x.month_accrued, 0))
const totalUsageAccrued = computed(() => accounts.value.filter((x) => x.account === '平台使用费账户').reduce((s, x) => s + x.total_accrued, 0))
const pendingInvoices = computed(() => invoices.value.filter((x) => x.status === 'pending').length)
const { page: accountsPage, pageSize: accountsPageSize, total: accountsPagedTotal, paged: pagedAccounts } = usePagedList(accounts)
const { page: invoicesPage, pageSize: invoicesPageSize, total: invoicesPagedTotal, paged: pagedInvoices } = usePagedList(invoices)

// ---- recharge ----
const rechargeVisible = ref(false)
const rechargeForm = reactive({ enterpriseId: null as number | null, enterpriseName: '', account: 'premium' as 'premium' | 'usage', amount: 0 })
function openRecharge(row: BillingRow) {
  rechargeForm.enterpriseId = row.id
  rechargeForm.enterpriseName = row.enterprise_name
  rechargeForm.account = row.account_type === 'premium' ? 'premium' : 'usage'
  rechargeForm.amount = 0
  rechargeVisible.value = true
}
async function submitRecharge() {
  if (!rechargeForm.enterpriseId || rechargeForm.amount < 0.01) { ElMessage.error('请输入充值金额'); return }
  try {
    await rechargeEnterprise(rechargeForm.enterpriseId, rechargeForm.account, rechargeForm.amount)
    ElMessage.success('充值成功')
    rechargeVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- ledger ----
const ledgerVisible = ref(false)
const ledgerTarget = ref<BillingRow | null>(null)
const ledgerData = ref<LedgerResponse | null>(null)
async function openLedger(row: BillingRow) {
  ledgerTarget.value = row
  ledgerVisible.value = true
  ledgerData.value = await getEnterpriseLedger(row.id)
}

// ---- invoices ----
const invoiceVisible = ref(false)
const invoiceForm = reactive({ enterprise_id: null as number | null, account: 'premium' as 'premium' | 'usage', amount: 0, title: '', tax_no: '', email: '' })
function openInvoiceCreate() {
  Object.assign(invoiceForm, { enterprise_id: auth.isEnterprise() ? auth.user?.enterprise_id ?? null : null, account: 'premium', amount: 0, title: '', tax_no: '', email: '' })
  invoiceVisible.value = true
}
async function submitInvoice() {
  if (!invoiceForm.enterprise_id || invoiceForm.amount <= 0 || !invoiceForm.title) { ElMessage.error('请选择投保单位、填写金额和发票抬头'); return }
  try {
    await financeApi.createInvoice({ ...invoiceForm, enterprise_id: invoiceForm.enterprise_id })
    ElMessage.success('发票申请已提交')
    invoiceVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function setInvoiceStatus(item: Invoice, status: string) {
  try {
    await ElMessageBox.confirm(`确认将发票状态更新为「${INVOICE_STATUS_TEXT[status]}」？`, '状态确认', { type: 'warning' })
  } catch { return }
  try {
    await financeApi.updateInvoiceStatus(item.id, status)
    ElMessage.success('发票状态已更新')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="finance-view">
    <div class="stat-grid">
      <StatTile label="保费账户余额合计" :value="money(totalPremium)" />
      <StatTile label="使用费账户余额合计" :value="money(totalUsage)" />
      <StatTile label="本月累计平台使用费" :value="money(monthUsageAccrued)" />
      <StatTile label="历史累计平台使用费" :value="money(totalUsageAccrued)" />
      <StatTile label="待审核发票" :value="pendingInvoices" hint-type="warning" />
    </div>

    <PageCard title="账户余额" :count="accounts.length" hint="平台使用费 = 每人日费率 × 实际有效参保人天，累计截止今天">
      <template #actions>
        <el-button type="primary" @click="openInvoiceCreate">＋ 申请发票</el-button>
      </template>
      <el-table :data="pagedAccounts" size="small">
        <el-table-column prop="enterprise_name" label="投保单位" min-width="150" />
        <el-table-column prop="account" label="账户" width="140" />
        <el-table-column label="余额" width="110"><template #default="{ row }">{{ money(row.balance) }}</template></el-table-column>
        <el-table-column label="计费单价 / 今日" width="155">
          <template #default="{ row }"><template v-if="row.account === '平台使用费账户'"><div>{{ money(row.daily_rate) }} / 人 / 天</div><small class="muted">{{ row.active_people }} 人 · {{ money(row.estimated_daily) }}</small></template><span v-else>—</span></template>
        </el-table-column>
        <el-table-column label="本月有效人天" width="115"><template #default="{ row }">{{ row.account === '平台使用费账户' ? `${row.month_person_days} 人天` : '—' }}</template></el-table-column>
        <el-table-column label="本月累计" width="110"><template #default="{ row }">{{ row.account === '平台使用费账户' ? money(row.month_accrued) : '—' }}</template></el-table-column>
        <el-table-column label="历史累计" width="110"><template #default="{ row }">{{ row.account === '平台使用费账户' ? money(row.total_accrued) : '—' }}</template></el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="auth.isAdmin() && row.account_type !== 'premium'" link type="primary" size="small" @click="openRecharge(row)">充值</el-button>
            <el-button link type="primary" size="small" @click="openLedger(row)">账本明细</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="accountsPage" v-model:page-size="accountsPageSize" :total="accountsPagedTotal" />
    </PageCard>

    <PageCard title="发票申请" :count="invoices.length" hint="企业提交、平台审核并登记开票状态">
      <el-table :data="pagedInvoices" size="small">
        <el-table-column label="申请时间" width="150"><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></el-table-column>
        <el-table-column prop="enterprise_name" label="投保单位" min-width="130" />
        <el-table-column label="发票抬头" min-width="140">
          <template #default="{ row }">
            <div>{{ row.title }}</div>
            <small class="muted">{{ row.tax_no || '未填税号' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="账户 / 金额" width="140">
          <template #default="{ row }">{{ row.account === 'premium' ? '保费' : '使用费' }} · {{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'issued' ? 'success' : row.status === 'rejected' ? 'danger' : 'warning'">{{ INVOICE_STATUS_TEXT[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin()" label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status !== 'issued'">
              <el-button link type="primary" size="small" @click="setInvoiceStatus(row, 'approved')">审核</el-button>
              <el-button link type="success" size="small" @click="setInvoiceStatus(row, 'issued')">已开票</el-button>
              <el-button link type="danger" size="small" @click="setInvoiceStatus(row, 'rejected')">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="invoicesPage" v-model:page-size="invoicesPageSize" :total="invoicesPagedTotal" />
    </PageCard>

    <el-dialog v-model="rechargeVisible" title="账户充值" width="420px">
      <el-form label-width="90px">
        <el-form-item label="投保单位"><span>{{ rechargeForm.enterpriseName }}</span></el-form-item>
        <el-form-item label="充值账户"><span>{{ rechargeForm.account === 'premium' ? '保费账户' : '服务费账户' }}</span></el-form-item>
        <el-form-item label="充值金额"><el-input-number v-model="rechargeForm.amount" :min="0.01" :step="100" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRecharge">确认充值</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ledgerVisible" title="账本明细" width="640px">
      <template v-if="ledgerTarget">
        <p class="ledger-title">{{ ledgerTarget.enterprise_name }} · {{ ledgerTarget.account }}</p>
        <div v-if="ledgerData" class="reconciliation-row">
          <div v-if="!ledgerData.reconciliation.length" class="reconciliation-item ok">
            <span>账本流水与账户余额对账正常，无差异</span>
          </div>
          <div v-for="r in ledgerData.reconciliation" :key="r.account" class="reconciliation-item">
            <span>{{ r.account === 'premium' ? '保费账户' : '服务费账户' }}对账异常</span>
            <b class="diff-warning">缓存 {{ money(r.cached_balance) }} / 流水 {{ money(r.ledger_balance) }} / 差额 {{ money(r.diff) }}</b>
          </div>
        </div>
        <el-table :data="ledgerData?.entries ?? []" size="small" style="margin-top: 12px">
          <el-table-column label="时间" width="140"><template #default="{ row }">{{ formatDateTime(row.occurred_at) }}</template></el-table-column>
          <el-table-column label="账户" width="90"><template #default="{ row }">{{ row.account === 'premium' ? '保费' : '服务费' }}</template></el-table-column>
          <el-table-column label="方向" width="70">
            <template #default="{ row }"><el-tag size="small" :type="row.direction === 'credit' ? 'success' : 'danger'">{{ row.direction === 'credit' ? '入账' : '出账' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="金额" width="100"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column prop="business_type" label="业务类型" width="100" />
          <el-table-column prop="operator" label="操作人" width="90" />
        </el-table>
      </template>
    </el-dialog>

    <el-dialog v-model="invoiceVisible" title="申请发票" width="480px">
      <el-form :model="invoiceForm" label-width="100px">
        <el-form-item label="投保单位" required>
          <el-select v-model="invoiceForm.enterprise_id" :disabled="auth.isEnterprise()" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="费用账户">
          <el-select v-model="invoiceForm.account" style="width: 100%">
            <el-option label="保费账户" value="premium" />
            <el-option label="平台使用费账户" value="usage" />
          </el-select>
        </el-form-item>
        <el-form-item label="开票金额" required><el-input-number v-model="invoiceForm.amount" :min="0.01" :step="100" /></el-form-item>
        <el-form-item label="发票抬头" required><el-input v-model="invoiceForm.title" /></el-form-item>
        <el-form-item label="纳税人识别号"><el-input v-model="invoiceForm.tax_no" /></el-form-item>
        <el-form-item label="接收邮箱"><el-input v-model="invoiceForm.email" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invoiceVisible = false">取消</el-button>
        <el-button type="primary" @click="submitInvoice">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.finance-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.ledger-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin: 0 0 10px;
}
.reconciliation-row {
  display: grid;
  gap: 6px;
}
.reconciliation-item {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 8px 12px;
  border-radius: 6px;
}
.diff-warning {
  color: var(--el-color-danger);
}
.reconciliation-item.ok {
  color: var(--el-color-success);
  justify-content: flex-start;
}
</style>
