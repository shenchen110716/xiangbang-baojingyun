<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as agentsApi from '@/api/agents'
import type { AgentCommission } from '@/api/types'
import { money, commissionModeText } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'
import DetailModal from '@/components/DetailModal.vue'
import AgentCommissionDialog from '@/views/agents/AgentCommissionDialog.vue'

const loading = ref(true)
const list = ref<AgentCommission[]>([])
const search = ref('')
const modeFilter = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await agentsApi.listAgentCommissions()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  let rows = list.value
  if (modeFilter.value) rows = rows.filter((x) => x.mode === modeFilter.value)
  if (search.value) {
    const q = search.value.toLowerCase()
    rows = rows.filter((x) => [x.agent_name, x.enterprise_name, x.insurer, x.plan_name].some((v) => (v || '').toLowerCase().includes(q)))
  }
  return rows
})

const totalFloor = computed(() => list.value.reduce((s, x) => s + (x.policy_floor_price || 0), 0))
const totalCommission = computed(() => list.value.reduce((s, x) => s + (x.accrued_total_commission || 0), 0))
const totalAgentCommission = computed(() => list.value.reduce((s, x) => s + (x.accrued_agent_commission || 0), 0))

const detailVisible = ref(false)
const detailItem = ref<AgentCommission | null>(null)
function openDetail(item: AgentCommission) {
  detailItem.value = item
  detailVisible.value = true
}

const dialogVisible = ref(false)
const editingItem = ref<AgentCommission | null>(null)
function openCreate() {
  editingItem.value = null
  dialogVisible.value = true
}
function openEdit(item: AgentCommission) {
  editingItem.value = item
  dialogVisible.value = true
}

async function removeItem(item: AgentCommission) {
  try {
    await ElMessageBox.confirm('确认删除该业务计价关系？', '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await agentsApi.deleteAgentCommission(item.id)
    ElMessage.success('业务关系已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="promotion-view">
    <div class="stat-grid">
      <StatTile label="业务关系" :value="list.length" />
      <StatTile label="保司结算底价合计" :value="money(totalFloor)" />
      <StatTile label="累计总返佣" :value="money(totalCommission)" />
      <StatTile label="累计业务员佣金" :value="money(totalAgentCommission)" />
    </div>

    <PageCard title="业务计价列表" :count="filtered.length" hint="返佣与业务员佣金按实际参保天数累计至今日；其余价格为元/人/计费周期">
      <template #actions>
        <el-button type="primary" @click="openCreate">＋ 新增业务</el-button>
      </template>
      <div class="filter-row">
        <FilterBar v-model:search="search">
          <el-select v-model="modeFilter" placeholder="全部模式" clearable style="width: 150px">
            <el-option label="按比例返佣" value="rebate" />
            <el-option label="输入销售价格" value="price" />
          </el-select>
        </FilterBar>
      </div>
      <el-table :data="filtered" size="small">
        <el-table-column label="业务员 / 单位" min-width="150">
          <template #default="{ row }">
            <div><b>{{ row.agent_name }}</b></div>
            <small class="muted">{{ row.enterprise_name }}</small>
          </template>
        </el-table-column>
        <el-table-column label="产品" min-width="130">
          <template #default="{ row }">
            <div>{{ row.insurer }}</div>
            <small class="muted">{{ row.plan_name }}</small>
          </template>
        </el-table-column>
        <el-table-column label="返佣模式" width="120">
          <template #default="{ row }">
            <div>{{ commissionModeText(row.mode) }}</div>
            <small class="muted">{{ row.mode === 'rebate' ? `${(row.rate * 100).toFixed(1)}%` : '直接输入价格' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="保险原价" width="100"><template #default="{ row }">{{ money(row.insurance_base_price) }}</template></el-table-column>
        <el-table-column label="保司结算底价" width="110"><template #default="{ row }">{{ money(row.policy_floor_price) }}</template></el-table-column>
        <el-table-column label="销售最低价" width="110"><template #default="{ row }">{{ money(row.minimum_sale_price) }}</template></el-table-column>
        <el-table-column label="实际销售价" width="100"><template #default="{ row }">{{ money(row.sale_price) }}</template></el-table-column>
        <el-table-column label="累计总返佣" width="125"><template #default="{ row }"><div>{{ money(row.accrued_total_commission) }}</div><small class="muted">单价 {{ money(row.total_commission_amount) }}</small></template></el-table-column>
        <el-table-column label="累计业务员佣金" width="135"><template #default="{ row }"><div>{{ money(row.accrued_agent_commission) }}</div><small class="muted">单价 {{ money(row.agent_commission_amount) }}</small></template></el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '推广中' : '已暂停' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeItem(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <DetailModal v-model="detailVisible" title="业务计价详情">
      <template v-if="detailItem">
        <p><b>{{ detailItem.agent_name }}</b> · {{ detailItem.enterprise_name }} · {{ detailItem.plan_name }}</p>
        <p>保险原价：{{ money(detailItem.insurance_base_price) }}</p>
        <p>总返佣单价：{{ money(detailItem.total_commission_amount) }}（{{ ((detailItem.total_commission_rate || 0) * 100).toFixed(1) }}%）</p>
        <p>累计总返佣：{{ money(detailItem.accrued_total_commission) }}（截至 {{ detailItem.accrual_as_of }}）</p>
        <p>保司结算底价：{{ money(detailItem.policy_floor_price) }}</p>
        <p>平台利润：{{ money(detailItem.profit_amount) }}</p>
        <p>销售最低价：{{ money(detailItem.minimum_sale_price) }}</p>
        <p>实际销售价：{{ money(detailItem.sale_price) }}</p>
        <p>返佣模式：{{ commissionModeText(detailItem.mode) }}</p>
        <p>业务员佣金单价：{{ money(detailItem.agent_commission_amount) }}</p>
        <p>累计业务员佣金：{{ money(detailItem.accrued_agent_commission) }}</p>
      </template>
    </DetailModal>

    <AgentCommissionDialog v-model="dialogVisible" :item="editingItem" @saved="load" />
  </div>
</template>

<style scoped>
.promotion-view {
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
