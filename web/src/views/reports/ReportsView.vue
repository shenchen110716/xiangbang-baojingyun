<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listEnterprises } from '@/api/enterprises'
import { listAgents } from '@/api/agents'
import { listPlans } from '@/api/plans'
import { getPremiumDetails, getReports } from '@/api/reports'
import type { Agent, Enterprise, PremiumDetailReport, ReportRow } from '@/api/types'
import { money } from '@/utils/format'
import { downloadAuthenticated } from '@/utils/download'
import { useAuthStore } from '@/stores/auth'
import PageCard from '@/components/PageCard.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()
const loading = ref(true)
const rows = ref<ReportRow[]>([])
const currencyIds = new Set(['premium', 'settlement', 'commission', 'usage_fee'])
const visibleRows = computed(() => auth.isEnterprise() ? rows.value.filter((row) => !['settlement', 'commission'].includes(row.id)) : rows.value)

const today = new Date()
const defaultMonth = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
const periodMode = ref<'month' | 'range'>('month')
const selectedMonth = ref(defaultMonth)
const selectedRange = ref<[string, string] | null>(null)
const premiumLoading = ref(false)
const premiumReport = ref<PremiumDetailReport | null>(null)
const premiumRows = computed(() => premiumReport.value?.rows || [])
const { page: premiumPage, pageSize: premiumPageSize, total: premiumPagedTotal, paged: pagedPremiumRows } = usePagedList(premiumRows)
const enterprises = ref<Enterprise[]>([])
const agents = ref<Agent[]>([])
const insurers = ref<string[]>([])
const selectedEnterprise = ref<number | null>(null)
const selectedAgent = ref<number | null>(null)
const selectedInsurer = ref('')

function monthRange(month: string): [string, string] {
  const [year, monthNumber] = month.split('-').map(Number)
  const lastDay = new Date(Date.UTC(year, monthNumber, 0)).getUTCDate()
  return [`${month}-01`, `${month}-${String(lastDay).padStart(2, '0')}`]
}

function reportRange(): [string, string] | null {
  if (periodMode.value === 'month') return monthRange(selectedMonth.value)
  return selectedRange.value
}

async function loadPremiumDetails() {
  const range = reportRange()
  if (!range) { ElMessage.warning('请选择统计时间段'); return }
  premiumLoading.value = true
  try {
    premiumReport.value = await getPremiumDetails(range[0], range[1], auth.isEnterprise() ? undefined : {
      enterprise_id: selectedEnterprise.value || undefined,
      insurer: selectedInsurer.value || undefined,
      agent_id: selectedAgent.value || undefined,
    })
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    premiumLoading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    rows.value = await getReports()
  } finally {
    loading.value = false
  }
}

async function loadPlatformFilters() {
  if (auth.isEnterprise()) return
  const [enterpriseRows, plans, agentRows] = await Promise.all([listEnterprises(), listPlans(), listAgents()])
  enterprises.value = enterpriseRows
  agents.value = agentRows
  insurers.value = [...new Set(plans.map((plan) => plan.insurer).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN'))
}
onMounted(async () => {
  try {
    await Promise.all([load(), loadPlatformFilters(), loadPremiumDetails()])
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
})

function displayValue(row: ReportRow) {
  return currencyIds.has(row.id) ? money(row.value) : String(row.value)
}

function exportCsv() {
  const header = ['报表', '周期', '指标', '说明']
  const csv = '﻿' + [header, ...visibleRows.value.map((r) => [r.name, r.period, displayValue(r), r.detail])]
    .map((r) => r.map((v) => `"${String(v).replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `响帮帮保经云-报表-${Date.now()}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}

async function exportPremiumDetails() {
  const range = reportRange()
  if (!range) { ElMessage.warning('请选择统计时间段'); return }
  try {
    const params = new URLSearchParams({ start_date: range[0], end_date: range[1] })
    if (!auth.isEnterprise() && selectedEnterprise.value) params.set('enterprise_id', String(selectedEnterprise.value))
    if (!auth.isEnterprise() && selectedInsurer.value) params.set('insurer', selectedInsurer.value)
    if (!auth.isEnterprise() && selectedAgent.value) params.set('agent_id', String(selectedAgent.value))
    const prefix = auth.isEnterprise() ? '销售保费明细' : '平台保费结算佣金明细'
    await downloadAuthenticated(`/reports/premium-details/export?${params.toString()}`, `${prefix}-${range[0]}-${range[1]}.xlsx`)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="reports-view">
    <div class="stat-grid">
      <div v-for="row in visibleRows" :key="row.id" class="stat-item">
        <span>{{ row.name }}</span>
        <b>{{ displayValue(row) }}</b>
      </div>
    </div>

    <PageCard title="报表中心" :count="visibleRows.length" :hint="auth.isEnterprise() ? '销售保费、平台使用费、参保人员和理赔数据统一统计' : '销售保费、保司结算、返佣和平台使用费统一统计'">
      <template #actions>
        <el-button type="primary" @click="exportCsv">导出报表</el-button>
      </template>
      <el-table :data="visibleRows" size="small">
        <el-table-column prop="name" label="报表名称" min-width="140" />
        <el-table-column prop="period" label="周期" min-width="160" />
        <el-table-column label="指标" width="140"><template #default="{ row }">{{ displayValue(row) }}</template></el-table-column>
        <el-table-column prop="detail" label="口径说明" min-width="200" />
      </el-table>
    </PageCard>

    <PageCard :title="auth.isEnterprise() ? '销售保费总额及明细' : '平台保费、结算与佣金统计'" :count="premiumReport?.detail_count || 0" :hint="auth.isEnterprise() ? '只累计实际已发生费用；按月方案按自然月天数折算' : '可按业务员与时间段统计佣金；导出文件严格使用当前查询条件'">
      <template #actions>
        <el-button :disabled="!premiumReport" @click="exportPremiumDetails">导出明细</el-button>
      </template>
      <div class="premium-filter">
        <el-select v-if="!auth.isEnterprise()" v-model="selectedAgent" clearable filterable placeholder="全部业务员" style="width: 180px">
          <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
        </el-select>
        <el-select v-if="!auth.isEnterprise()" v-model="selectedInsurer" clearable placeholder="全部保司" style="width: 210px">
          <el-option v-for="insurer in insurers" :key="insurer" :label="insurer" :value="insurer" />
        </el-select>
        <el-select v-if="!auth.isEnterprise()" v-model="selectedEnterprise" clearable filterable placeholder="全部投保单位" style="width: 240px">
          <el-option v-for="enterprise in enterprises" :key="enterprise.id" :label="enterprise.name" :value="enterprise.id" />
        </el-select>
        <el-radio-group v-model="periodMode">
          <el-radio-button value="month">按月</el-radio-button>
          <el-radio-button value="range">按时间段</el-radio-button>
        </el-radio-group>
        <el-date-picker v-if="periodMode === 'month'" v-model="selectedMonth" type="month" value-format="YYYY-MM" placeholder="选择月份" />
        <el-date-picker v-else v-model="selectedRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
        <el-button type="primary" :loading="premiumLoading" @click="loadPremiumDetails">查询</el-button>
      </div>
      <div v-if="premiumReport" class="premium-summary">
        <span>查询区间 {{ premiumReport.start_date }} 至 {{ premiumReport.end_date }}<br>实际累计截止 {{ premiumReport.as_of_date }}</span>
        <div class="premium-totals">
          <b>销售保费总额：{{ money(premiumReport.total_premium) }}</b>
          <b v-if="!auth.isEnterprise()">保司结算总额：{{ money(premiumReport.total_settlement) }}</b>
          <b v-if="!auth.isEnterprise()">总返佣金额：{{ money(premiumReport.total_commission) }}</b>
          <b v-if="!auth.isEnterprise()">业务员佣金：{{ money(premiumReport.total_agent_commission) }}</b>
        </div>
      </div>
      <el-table v-loading="premiumLoading" :data="pagedPremiumRows" size="small" empty-text="该时间段暂无保费明细">
        <el-table-column label="被保险人" min-width="150">
          <template #default="{ row }"><div>{{ row.person_name }}</div><small class="muted">{{ row.id_number }}</small></template>
        </el-table-column>
        <el-table-column v-if="!auth.isEnterprise()" prop="enterprise_name" label="投保单位" min-width="180" />
        <el-table-column v-if="!auth.isEnterprise()" prop="agent_name" label="业务员" min-width="110"><template #default="{ row }">{{ row.agent_name || '未分配' }}</template></el-table-column>
        <el-table-column label="实际用工单位 / 岗位" min-width="180">
          <template #default="{ row }"><div>{{ row.actual_employer_name || '—' }}</div><small class="muted">{{ row.position_name }} · {{ row.occupation_class }}</small></template>
        </el-table-column>
        <el-table-column label="保险方案 / 保单" min-width="190">
          <template #default="{ row }"><div>{{ row.insurer }} · {{ row.plan_name }}</div><small class="muted">{{ row.policy_no }}</small></template>
        </el-table-column>
        <el-table-column label="计费方式" width="90"><template #default="{ row }">{{ row.billing_mode === 'daily' ? '按天' : '按月' }}</template></el-table-column>
        <el-table-column label="实际销售价" width="120"><template #default="{ row }">{{ money(row.unit_sale_price) }}</template></el-table-column>
        <el-table-column v-if="!auth.isEnterprise()" label="保司结算底价" width="130"><template #default="{ row }">{{ money(row.unit_policy_floor_price) }}</template></el-table-column>
        <el-table-column label="本期保障时间" min-width="185"><template #default="{ row }">{{ row.period_start }} 至 {{ row.period_end }}</template></el-table-column>
        <el-table-column prop="active_days" label="计费天数" width="90" />
        <el-table-column label="保费金额" width="120"><template #default="{ row }"><b>{{ money(row.premium_amount) }}</b></template></el-table-column>
        <el-table-column v-if="!auth.isEnterprise()" label="保司结算金额" width="135"><template #default="{ row }"><b>{{ money(row.settlement_amount) }}</b></template></el-table-column>
        <el-table-column v-if="!auth.isEnterprise()" label="总返佣金额" width="125"><template #default="{ row }"><div><b>{{ money(row.commission_amount) }}</b></div><small class="muted">单价 {{ money(row.unit_total_commission) }}</small></template></el-table-column>
        <el-table-column v-if="!auth.isEnterprise()" label="业务员佣金" width="125"><template #default="{ row }"><div><b>{{ money(row.agent_commission_amount) }}</b></div><small class="muted">单价 {{ money(row.unit_agent_commission) }}</small></template></el-table-column>
      </el-table>
      <TablePagination v-model:page="premiumPage" v-model:page-size="premiumPageSize" :total="premiumPagedTotal" />
    </PageCard>
  </div>
</template>

<style scoped>
.reports-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.stat-item {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 9px;
  padding: 16px 18px;
  display: grid;
  gap: 10px;
}
.stat-item span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.stat-item b {
  font-size: 20px;
}
.premium-filter {
  padding: 0 20px 16px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.premium-summary {
  margin: 0 20px 16px;
  padding: 14px 16px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.premium-summary span,
.muted {
  color: var(--el-text-color-secondary);
}
.premium-summary b {
  color: var(--el-color-primary);
  font-size: 18px;
}
.premium-totals {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px 24px;
}
</style>
