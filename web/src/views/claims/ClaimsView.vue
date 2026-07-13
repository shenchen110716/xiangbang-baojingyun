<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import * as claimsApi from '@/api/claims'
import { CLAIM_RISK_TEXT, CLAIM_STATUS_TEXT } from '@/api/claims'
import type { Claim } from '@/api/types'
import { money } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'
import ClaimCreateDialog from './ClaimCreateDialog.vue'
import ClaimWorkbenchDialog from './ClaimWorkbenchDialog.vue'

const loading = ref(true)
const list = ref<Claim[]>([])
const search = ref('')
const statusFilter = ref('')
const riskFilter = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await claimsApi.listClaims()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  let rows = list.value
  if (statusFilter.value) rows = rows.filter((x) => x.status === statusFilter.value)
  if (riskFilter.value) rows = rows.filter((x) => x.calculated_risk === riskFilter.value)
  if (search.value) {
    const q = search.value.toLowerCase()
    rows = rows.filter((x) => [x.claim_no, x.person_name, x.enterprise_name, x.actual_employer_name].some((v) => (v || '').toLowerCase().includes(q)))
  }
  return rows
})

const openCount = computed(() => list.value.filter((x) => !['paid', 'closed', 'rejected'].includes(x.status)).length)
const highRiskCount = computed(() => list.value.filter((x) => x.calculated_risk === 'high').length)
const overdueCount = computed(() => list.value.filter((x) => x.deadline_overdue || x.sla_overdue).length)

const riskType: Record<string, string> = { normal: 'info', attention: 'warning', high: 'danger' }
const statusType: Record<string, string> = {
  reported: 'info', collecting: 'warning', submitted: 'warning', insurer_review: 'warning',
  supplement: 'danger', approved: 'success', paid: 'success', rejected: 'danger', closed: 'info',
}

const createVisible = ref(false)
const workbenchVisible = ref(false)
const activeClaimId = ref<number | null>(null)
function openWorkbench(item: Claim) {
  activeClaimId.value = item.id
  workbenchVisible.value = true
}
</script>

<template>
  <div v-loading="loading" class="claims-view">
    <div class="stat-grid">
      <StatTile label="案件总数" :value="list.length" />
      <StatTile label="处理中" :value="openCount" hint-type="warning" />
      <StatTile label="高风险" :value="highRiskCount" hint-type="danger" />
      <StatTile label="已逾期/超时" :value="overdueCount" hint-type="danger" />
    </div>

    <PageCard title="工伤理赔工作台" :count="filtered.length">
      <template #actions>
        <el-button type="primary" @click="createVisible = true">＋ 新增报案</el-button>
      </template>
      <div class="filter-row">
        <FilterBar v-model:search="search">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px">
            <el-option v-for="(text, key) in CLAIM_STATUS_TEXT" :key="key" :label="text" :value="key" />
          </el-select>
          <el-select v-model="riskFilter" placeholder="全部风险" clearable style="width: 120px">
            <el-option v-for="(text, key) in CLAIM_RISK_TEXT" :key="key" :label="text" :value="key" />
          </el-select>
        </FilterBar>
      </div>
      <el-table :data="filtered" size="small">
        <el-table-column label="案件号 / 被保险人" min-width="160">
          <template #default="{ row }">
            <div><b>{{ row.claim_no }}</b></div>
            <small class="muted">{{ row.person_name }} · {{ row.enterprise_name }}</small>
          </template>
        </el-table-column>
        <el-table-column label="事故信息" min-width="150">
          <template #default="{ row }">
            <div>{{ row.accident_type }}</div>
            <small class="muted">{{ row.accident_at }}</small>
          </template>
        </el-table-column>
        <el-table-column label="材料进度" width="110">
          <template #default="{ row }">
            <el-progress :percentage="row.complete_percent" :status="row.complete_percent === 100 ? 'success' : undefined" />
          </template>
        </el-table-column>
        <el-table-column label="金额" width="110">
          <template #default="{ row }">{{ money(row.approved_amount || row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="statusType[row.status] as any">{{ CLAIM_STATUS_TEXT[row.status] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="风险" width="90">
          <template #default="{ row }"><el-tag size="small" :type="riskType[row.calculated_risk] as any">{{ CLAIM_RISK_TEXT[row.calculated_risk] || row.calculated_risk }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openWorkbench(row)">处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <ClaimCreateDialog v-model="createVisible" @created="load" />
    <ClaimWorkbenchDialog v-model="workbenchVisible" :claim-id="activeClaimId" @changed="load" />
  </div>
</template>

<style scoped>
.claims-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.filter-row {
  padding: 0 20px 14px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
