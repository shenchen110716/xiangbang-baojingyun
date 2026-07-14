<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listPlans } from '@/api/plans'
import type { InsurancePlan } from '@/api/types'
import { money } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'
import DetailModal from '@/components/DetailModal.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const router = useRouter()
const loading = ref(true)
const list = ref<InsurancePlan[]>([])
const search = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await listPlans()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  if (!search.value) return list.value
  const q = search.value.toLowerCase()
  return list.value.filter((x) => [x.insurer, x.name, x.insurer_email].some((v) => (v || '').toLowerCase().includes(q)))
})
const { page, pageSize, total: pagedTotal, paged } = usePagedList(filtered)
const activeCount = computed(() => list.value.filter((x) => x.status === 'active').length)
const immediateCount = computed(() => list.value.filter((x) => x.effective_mode === 'immediate' && x.billing_mode === 'daily').length)

const detailVisible = ref(false)
const detailItem = ref<InsurancePlan | null>(null)
function openDetail(item: InsurancePlan) {
  detailItem.value = item
  detailVisible.value = true
}
</script>

<template>
  <div v-loading="loading" class="products-view">
    <div class="stat-grid">
      <StatTile label="产品方案" :value="list.length" />
      <StatTile label="在售方案" :value="activeCount" hint-type="success" />
      <StatTile label="即时按天" :value="immediateCount" />
    </div>

    <PageCard title="保险产品管理" :count="filtered.length" hint="保险原价、保司结算、利润、销售最低价和返佣金额统一展示">
      <template #actions>
        <el-button type="primary" @click="router.push({ name: 'insurers' })">＋ 维护保险方案</el-button>
      </template>
      <div class="filter-row"><FilterBar v-model:search="search" /></div>
      <el-table :data="paged" size="small">
        <el-table-column label="保险公司" min-width="150">
          <template #default="{ row }">
            <div>{{ row.insurer }}</div>
            <small class="muted">{{ row.insurer_email || '未设置邮箱' }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="产品方案" min-width="140" />
        <el-table-column label="保险原价" width="100">
          <template #default="{ row }">{{ money(row.insurance_base_price) }}</template>
        </el-table-column>
        <el-table-column label="总返佣金额" width="120">
          <template #default="{ row }">
            <div>{{ money(row.total_commission_amount) }}</div>
            <small class="muted">{{ (row.total_commission_rate * 100).toFixed(1) }}%</small>
          </template>
        </el-table-column>
        <el-table-column label="保司结算底价" width="110">
          <template #default="{ row }">{{ money(row.policy_floor_price) }}</template>
        </el-table-column>
        <el-table-column label="平台利润" width="100">
          <template #default="{ row }">{{ money(row.profit_amount) }}</template>
        </el-table-column>
        <el-table-column label="销售最低价" width="110">
          <template #default="{ row }">{{ money(row.minimum_sale_price) }}</template>
        </el-table-column>
        <el-table-column label="计费/生效" width="110">
          <template #default="{ row }">
            <div>{{ row.billing_mode === 'daily' ? '按天' : '按月' }}</div>
            <small class="muted">{{ row.effective_mode === 'immediate' ? '即时生效' : '次日生效' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '在售' : '暂停' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">计价详情</el-button>
            <el-button link type="primary" size="small" @click="router.push({ name: 'insurers' })">维护</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <DetailModal v-model="detailVisible" title="产品计价详情">
      <template v-if="detailItem">
        <p>{{ detailItem.insurer }} · {{ detailItem.name }}</p>
        <p>保险原价：{{ money(detailItem.insurance_base_price) }}</p>
        <p>总返佣金额：{{ money(detailItem.total_commission_amount) }}</p>
        <p>保司结算底价：{{ money(detailItem.policy_floor_price) }}</p>
        <p>平台利润：{{ money(detailItem.profit_amount) }}</p>
        <p>销售最低价：{{ money(detailItem.minimum_sale_price) }}</p>
        <p>生效计费：{{ detailItem.effective_mode === 'immediate' ? '即时生效、按天计费' : detailItem.billing_mode === 'daily' ? '次日生效、按天计费' : '次日生效、按月计费' }}</p>
      </template>
    </DetailModal>
  </div>
</template>

<style scoped>
.products-view {
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
