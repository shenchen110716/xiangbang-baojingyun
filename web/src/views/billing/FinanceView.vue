<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as financeApi from '@/api/finance'
import { INVOICE_STATUS_TEXT } from '@/api/finance'
import { getBilling } from '@/api/reports'
import { listEnterprises, getEnterpriseLedger } from '@/api/enterprises'
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

const totalPremium = computed(() => accounts.value.filter((x) => x.account_type === 'premium').reduce((s, x) => s + (x.available ?? x.balance), 0))
const totalPremiumRecharged = computed(() => accounts.value.filter((x) => x.account_type === 'premium').reduce((s, x) => s + (x.recharged ?? x.balance), 0))
const totalPremiumConsumed = computed(() => accounts.value.filter((x) => x.account_type === 'premium').reduce((s, x) => s + (x.premium_consumed ?? 0), 0))
const totalUsage = computed(() => accounts.value.filter((x) => x.account === '平台使用费账户').reduce((s, x) => s + x.balance, 0))
const totalUsageAvailable = computed(() => accounts.value.filter((x) => x.account === '平台使用费账户').reduce((s, x) => s + (x.available ?? x.balance), 0))
const monthUsageAccrued = computed(() => accounts.value.filter((x) => x.account === '平台使用费账户').reduce((s, x) => s + x.month_accrued, 0))
const totalUsageAccrued = computed(() => accounts.value.filter((x) => x.account === '平台使用费账户').reduce((s, x) => s + x.total_accrued, 0))
const pendingInvoices = computed(() => invoices.value.filter((x) => x.status === 'pending').length)
const { page: accountsPage, pageSize: accountsPageSize, total: accountsPagedTotal, paged: pagedAccounts } = usePagedList(accounts)
const { page: invoicesPage, pageSize: invoicesPageSize, total: invoicesPagedTotal, paged: pagedInvoices } = usePagedList(invoices)

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
const invoiceForm = reactive({ enterprise_id: null as number | null, account: 'premium' as 'premium' | 'usage', invoice_type: '增值税普通发票', amount: 0, title: '', tax_no: '', email: '' })
const invoiceSummary = ref<financeApi.InvoiceMonthlySummary | null>(null)
const invoiceAlreadyDone = computed(() => invoiceSummary.value ? invoiceSummary.value[invoiceForm.account].invoiced : false)
// 按自然月自动带出当月应开票金额（保费 / 系统服务费），并提示本月是否已开票。
async function refreshInvoiceAmount() {
  if (!invoiceForm.enterprise_id) { invoiceSummary.value = null; return }
  try {
    invoiceSummary.value = await financeApi.getInvoiceMonthlySummary(invoiceForm.enterprise_id)
    invoiceForm.amount = invoiceSummary.value[invoiceForm.account].amount
  } catch { invoiceSummary.value = null }
}
// 发票抬头/税号填过一次就记住，下次申请直接带出来，不用重新输入；同一单位
// 用过多个抬头时，允许在下方下拉里选（用户反馈 2026-07-29）。
const invoiceTitleHistory = ref<financeApi.InvoiceTitleHistory[]>([])
async function refreshInvoiceTitles() {
  if (!invoiceForm.enterprise_id) { invoiceTitleHistory.value = []; return }
  try {
    invoiceTitleHistory.value = await financeApi.getInvoiceTitles(invoiceForm.enterprise_id)
    if (invoiceTitleHistory.value.length === 1 && !invoiceForm.title && !invoiceForm.tax_no) {
      applyInvoiceTitle(invoiceTitleHistory.value[0])
    }
  } catch { invoiceTitleHistory.value = [] }
}
function invoiceTitleSuggestions(query: string, callback: (results: { value: string; tax_no: string }[]) => void) {
  const options = invoiceTitleHistory.value.map((h) => ({ value: h.title, tax_no: h.tax_no }))
  callback(query ? options.filter((o) => o.value.toLowerCase().includes(query.toLowerCase())) : options)
}
function applyInvoiceTitle(entry: financeApi.InvoiceTitleHistory | { value: string; tax_no: string }) {
  invoiceForm.title = 'value' in entry ? entry.value : entry.title
  invoiceForm.tax_no = entry.tax_no
}
function openInvoiceCreate() {
  Object.assign(invoiceForm, { enterprise_id: auth.isEnterprise() ? auth.user?.enterprise_id ?? null : null, account: 'premium', invoice_type: '增值税普通发票', amount: 0, title: '', tax_no: '', email: '' })
  invoiceSummary.value = null
  invoiceTitleHistory.value = []
  invoiceVisible.value = true
  refreshInvoiceAmount()
  refreshInvoiceTitles()
}
watch(() => [invoiceForm.enterprise_id, invoiceForm.account], refreshInvoiceAmount)
watch(() => invoiceForm.enterprise_id, refreshInvoiceTitles)
async function submitInvoice() {
  if (!invoiceForm.enterprise_id || invoiceForm.amount <= 0) { ElMessage.error('请选择投保单位、填写金额'); return }
  if (!invoiceForm.title.trim() || !invoiceForm.tax_no.trim()) { ElMessage.error('发票抬头和纳税人识别号必须填写完整才能申请'); return }
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

// 平台端上传发票文件（用户反馈 2026-07-29：申请/上传/下载三端齐全）；
// 保司端在自己的工作台上传，这里只服务平台管理员。
async function handleInvoiceUpload(item: Invoice, file: File) {
  try {
    await financeApi.uploadInvoiceDocument(item.id, file)
    ElMessage.success('发票文件已上传')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="finance-view">
    <div class="stat-grid">
      <StatTile label="保费可用余额合计" :value="money(totalPremium)" :hint="`充值 ${money(totalPremiumRecharged)} · 销售保费 ${money(totalPremiumConsumed)}`" hint-type="info" />
      <StatTile label="使用费可用余额合计" :value="money(totalUsageAvailable)" :hint="`充值 ${money(totalUsage)} · 已用 ${money(totalUsageAccrued)}`" hint-type="info" />
      <StatTile label="本月累计平台使用费" :value="money(monthUsageAccrued)" />
      <StatTile label="历史累计平台使用费" :value="money(totalUsageAccrued)" />
      <StatTile label="待审核发票" :value="pendingInvoices" hint-type="warning" />
    </div>

    <PageCard title="账户余额" :count="accounts.length" hint="平台使用费 = 每人日费率 × 实际有效参保人天，累计截止今天">
      <template #actions>
        <el-button type="primary" @click="openInvoiceCreate">＋ 申请发票</el-button>
      </template>
      <el-table :data="pagedAccounts" size="small">
        <!-- 账户名折进单位名下行：同一单位的保费/使用费两行靠小字区分，省一列 -->
        <el-table-column label="投保单位/账户" min-width="180">
          <template #default="{ row }">
            <div>{{ row.enterprise_name }}</div>
            <div class="muted">ID: {{ row.id }} · {{ row.account }}</div>
          </template>
        </el-table-column>
        <el-table-column label="可用余额" width="150"><template #default="{ row }"><div>{{ money(row.available ?? row.balance) }}</div><small class="muted">充值 {{ money(row.recharged ?? row.balance) }} · {{ row.account === '平台使用费账户' ? '已用' : '销售保费' }} {{ money(row.account === '平台使用费账户' ? row.total_accrued : (row.premium_consumed ?? 0)) }}</small></template></el-table-column>
        <el-table-column label="计费单价 / 今日" width="155">
          <template #default="{ row }"><template v-if="row.account === '平台使用费账户'"><div>{{ money(row.daily_rate) }} / 人 / 天</div><small class="muted">{{ row.active_people }} 人 · {{ money(row.estimated_daily) }}</small></template><span v-else>—</span></template>
        </el-table-column>
        <!-- 本月累计 = 单价 × 本月有效人天，人天是它的构成明细，折进下行而不是单独占列 -->
        <el-table-column label="本月累计" width="120">
          <template #default="{ row }">
            <template v-if="row.account === '平台使用费账户'">
              <div>{{ money(row.month_accrued) }}</div>
              <div class="muted">{{ row.month_person_days }} 人天</div>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="历史累计" width="110"><template #default="{ row }">{{ row.account === '平台使用费账户' ? money(row.total_accrued) : '—' }}</template></el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openLedger(row)">账本明细</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="accountsPage" v-model:page-size="accountsPageSize" :total="accountsPagedTotal" />
    </PageCard>

    <PageCard title="发票申请" :count="invoices.length" hint="企业提交、平台审核并登记开票状态">
      <el-table :data="pagedInvoices" size="small">
        <el-table-column label="投保单位" min-width="130">
          <template #default="{ row }">
            <div>{{ row.enterprise_name }}</div>
            <small class="muted">ID: {{ row.enterprise_id }}</small>
          </template>
        </el-table-column>
        <el-table-column label="发票抬头 / 税号" min-width="160">
          <template #default="{ row }">
            <div>{{ row.title }}</div>
            <small class="muted">{{ row.tax_no || '未填税号' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="账户金额/票类" width="150">
          <template #default="{ row }">
            <div>{{ row.account === 'premium' ? '保费' : '使用费' }} · {{ money(row.amount) }}</div>
            <div class="muted">{{ row.invoice_type }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态/申请时间" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'issued' ? 'success' : row.status === 'rejected' ? 'danger' : 'warning'">{{ INVOICE_STATUS_TEXT[row.status] }}</el-tag>
            <div class="muted">申请 {{ formatDateTime(row.created_at) }}</div>
          </template>
        </el-table-column>
        <!-- 发票文件和接收邮箱都是"票开出来交付到哪"的信息，合成一列 -->
        <el-table-column label="发票文件/接收邮箱" min-width="150">
          <template #default="{ row }">
            <div>
              <a v-if="row.document_download_url" :href="row.document_download_url" target="_blank">{{ row.document_name || '下载' }}</a>
              <span v-else class="muted">未上传</span>
            </div>
            <div class="muted">{{ row.email || '未填邮箱' }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin()" label="操作" width="215" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status !== 'issued'">
              <el-button link type="primary" size="small" @click="setInvoiceStatus(row, 'approved')">审核</el-button>
              <el-button link type="success" size="small" @click="setInvoiceStatus(row, 'issued')">已开票</el-button>
            </template>
            <el-upload class="upload-inline" :show-file-list="false" :auto-upload="false" accept=".pdf,.jpg,.jpeg,.png"
                       @change="(f: { raw: File }) => handleInvoiceUpload(row, f.raw)">
              <el-button link type="primary" size="small">{{ row.document_download_url ? '重新上传' : '上传发票' }}</el-button>
            </el-upload>
            <!-- 驳回低频且危险，收进显式「更多」，避免四个按钮挤爆操作列 -->
            <el-dropdown v-if="row.status !== 'issued'" class="op-more" trigger="click">
              <el-button link type="primary" size="small">更多<el-icon class="el-icon--right"><arrow-down /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <!-- dropdown 菜单被 teleport 到 body，scoped 样式够不着，红色只能内联 -->
                  <el-dropdown-item @click="setInvoiceStatus(row, 'rejected')"><span style="color: var(--el-color-danger)">驳回</span></el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="invoicesPage" v-model:page-size="invoicesPageSize" :total="invoicesPagedTotal" />
    </PageCard>

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
        <!-- 640px 弹窗塞 6 列太挤：方向标签跟金额同行（方向本来就是金额的正负号），
             业务类型折进账户下行 -->
        <el-table :data="ledgerData?.entries ?? []" size="small" style="margin-top: 12px">
          <el-table-column label="时间" width="150"><template #default="{ row }">{{ formatDateTime(row.occurred_at) }}</template></el-table-column>
          <el-table-column label="账户/业务类型" min-width="120">
            <template #default="{ row }">
              <div>{{ row.account === 'premium' ? '保费' : '服务费' }}</div>
              <div class="muted">{{ row.business_type }}</div>
            </template>
          </el-table-column>
          <el-table-column label="方向/金额" width="150">
            <template #default="{ row }"><el-tag size="small" :type="row.direction === 'credit' ? 'success' : 'danger'">{{ row.direction === 'credit' ? '入账' : '出账' }}</el-tag> {{ money(row.amount) }}</template>
          </el-table-column>
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
            <el-option label="系统服务费账户" value="usage" />
          </el-select>
        </el-form-item>
        <el-form-item label="票类" required>
          <el-select v-model="invoiceForm.invoice_type" style="width: 100%">
            <el-option label="增值税普通发票" value="增值税普通发票" />
            <el-option label="增值税专用发票" value="增值税专用发票" />
          </el-select>
        </el-form-item>
        <el-form-item label="开票金额" required>
          <el-input-number v-model="invoiceForm.amount" :min="0.01" :step="100" style="width: 100%" />
          <div v-if="invoiceSummary" class="muted" style="font-size: 12px; line-height: 1.6; margin-top: 4px">
            已自动带出 {{ invoiceSummary.month }} 当月{{ invoiceForm.account === 'premium' ? '保费' : '系统服务费' }}：{{ money(invoiceSummary[invoiceForm.account].amount) }}
            <span v-if="invoiceAlreadyDone" style="color: var(--el-color-warning)"> · 本月该账户已申请过发票</span>
          </div>
        </el-form-item>
        <el-form-item label="发票抬头" required>
          <el-autocomplete
            v-model="invoiceForm.title"
            :fetch-suggestions="invoiceTitleSuggestions"
            placeholder="必填，用过的抬头会自动列出"
            style="width: 100%"
            @select="applyInvoiceTitle"
          />
        </el-form-item>
        <el-form-item label="纳税人识别号" required>
          <el-input v-model="invoiceForm.tax_no" placeholder="必填" />
        </el-form-item>
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
  font-size: 12px;
}
.op-more {
  margin-left: 12px;
  vertical-align: middle;
}
/* el-upload 根节点是块级元素，不内联会把后面的「更多」挤到下一行 */
.upload-inline {
  display: inline-flex;
  vertical-align: middle;
  margin-left: 12px;
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
