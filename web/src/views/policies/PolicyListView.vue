<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listPolicies, uploadPolicyDocument } from '@/api/reports'
import { listInsured } from '@/api/insured'
import type { InsuredPerson, Policy } from '@/api/types'
import { money } from '@/utils/format'
import { downloadAuthenticated } from '@/utils/download'
import { useAuthStore } from '@/stores/auth'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'
import DetailModal from '@/components/DetailModal.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()

const loading = ref(true)
const list = ref<Policy[]>([])
const search = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await listPolicies()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  if (!search.value) return list.value
  const q = search.value.toLowerCase()
  return list.value.filter((x) => [x.policy_no, x.enterprise_name, x.insurer, x.plan_name].some((v) => (v || '').toLowerCase().includes(q)))
})
const { page, pageSize, total: pagedTotal, paged } = usePagedList(filtered)

const totalInsured = computed(() => list.value.reduce((sum, x) => sum + (x.insured_count || 0), 0))
const totalSalePremium = computed(() => list.value.reduce((sum, x) => sum + (x.sale_total ?? x.premium ?? 0), 0))
const totalCommission = computed(() => list.value.reduce((sum, x) => sum + (x.total_commission_total || 0), 0))

async function exportOne(item: Policy) {
  try {
    await downloadAuthenticated(`/policies/${item.id}/export`, `保单-${item.policy_no}.xlsx`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function openCertificate(item: Policy) {
  window.open(`/certificate/policy/${item.id}`, '_blank')
}

async function downloadDocument(item: Policy) {
  if (!item.document_download_url) return
  try {
    await downloadAuthenticated(item.document_download_url.replace(/^\/api/, ''), item.document_name || `保单文件-${item.policy_no}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const uploadingId = ref<number | null>(null)
const fileInputs = ref<Record<number, HTMLInputElement | null>>({})
function triggerUpload(item: Policy) {
  fileInputs.value[item.id]?.click()
}
async function onDocumentChange(item: Policy, e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploadingId.value = item.id
  try {
    await uploadPolicyDocument(item.id, file)
    ElMessage.success('保单文件已导入')
    load()
  } catch (err) {
    ElMessage.error((err as Error).message)
  } finally {
    uploadingId.value = null
  }
}

const detailVisible = ref(false)
const detailItem = ref<Policy | null>(null)
const detailPeople = ref<InsuredPerson[]>([])
async function openDetail(item: Policy) {
  detailItem.value = item
  detailVisible.value = true
  const all = await listInsured()
  detailPeople.value = all.filter((p) => p.policy_id === item.id)
}
</script>

<template>
  <div v-loading="loading" class="policy-list-view">
    <div class="stat-grid">
      <StatTile label="保单数" :value="list.length" />
      <StatTile label="参保员工" :value="totalInsured" />
      <StatTile label="销售保费" :value="money(totalSalePremium)" />
      <StatTile label="总返佣金额" :value="money(totalCommission)" />
    </div>

    <PageCard title="保单管理" :count="filtered.length" hint="保单保费与产品、佣金使用同一价格计算口径">
      <div class="filter-row"><FilterBar v-model:search="search" /></div>
      <el-table :data="paged" size="small">
        <el-table-column label="保单 / 单位" min-width="160">
          <template #default="{ row }">
            <div><b>{{ row.policy_no }}</b></div>
            <small class="muted">{{ row.enterprise_name }}</small>
          </template>
        </el-table-column>
        <el-table-column label="保险产品" min-width="140">
          <template #default="{ row }">
            <div>{{ row.insurer }}</div>
            <small class="muted">{{ row.plan_name }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="insured_count" label="人数" width="70" />
        <el-table-column label="保司结算底价" width="110"><template #default="{ row }">{{ money(row.policy_floor_total) }}</template></el-table-column>
        <el-table-column label="销售最低价" width="110"><template #default="{ row }">{{ money(row.minimum_sale_total) }}</template></el-table-column>
        <el-table-column label="实际销售保费" width="110"><template #default="{ row }">{{ money(row.sale_total ?? row.premium) }}</template></el-table-column>
        <el-table-column label="总返佣金额" width="110"><template #default="{ row }">{{ money(row.total_commission_total) }}</template></el-table-column>
        <el-table-column label="业务员佣金" width="110"><template #default="{ row }">{{ money(row.agent_commission_total) }}</template></el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="保单文件" width="110">
          <template #default="{ row }">
            <el-button v-if="row.document_download_url" link type="primary" size="small" @click="downloadDocument(row)">{{ row.document_name || '已导入' }}</el-button>
            <span v-else class="muted">未导入</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看计价</el-button>
            <el-button link type="primary" size="small" @click="exportOne(row)">导出明细</el-button>
            <el-button link type="primary" size="small" @click="openCertificate(row)">打印证明</el-button>
            <template v-if="auth.isAdmin()">
              <input :ref="(el) => (fileInputs[row.id] = el as HTMLInputElement)" type="file" accept=".pdf,.jpg,.jpeg,.png" style="display: none" @change="(e) => onDocumentChange(row, e)" />
              <el-button link type="primary" size="small" :loading="uploadingId === row.id" @click="triggerUpload(row)">导入保单</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <DetailModal v-model="detailVisible" title="保单详情">
      <template v-if="detailItem">
        <p>{{ detailItem.enterprise_name }} · {{ detailItem.insurer }} · {{ detailItem.plan_name }}</p>
        <p>保险原价：{{ money(detailItem.insurance_base_total) }}</p>
        <p>保司结算底价：{{ money(detailItem.policy_floor_total) }}</p>
        <p>销售最低价：{{ money(detailItem.minimum_sale_total) }}</p>
        <p>实际销售保费：{{ money(detailItem.sale_total ?? detailItem.premium) }}</p>
        <p>总返佣金额：{{ money(detailItem.total_commission_total) }}</p>
        <p>业务员佣金：{{ money(detailItem.agent_commission_total) }}</p>
        <p>计费规则：{{ detailItem.effective_mode === 'immediate' ? '即时生效 · 按天计费' : detailItem.billing_mode === 'daily' ? '按天计费' : '按月计费' }}</p>
        <p>保障期限：{{ detailItem.start_date || '—' }} 至 {{ detailItem.end_date || '—' }}</p>
        <p v-for="p in detailPeople" :key="p.id"><b>{{ p.name }}</b> · {{ p.actual_employer_name }} · {{ p.position_name }} · {{ p.occupation_class }}</p>
        <p v-if="!detailPeople.length">暂无关联被保险人</p>
      </template>
    </DetailModal>
  </div>
</template>

<style scoped>
.policy-list-view {
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
