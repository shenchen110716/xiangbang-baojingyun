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
      <StatTile label="保费" :value="money(totalSalePremium)" />
      <StatTile v-if="auth.isAdmin()" label="总返佣金额" :value="money(totalCommission)" />
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
        <!-- 底价/最低价是同一条价格链上的参考口径，折进销售保费下行；佣金是独立指标，
             只把"业务员佣金"（总返佣的组成部分）折进总返佣下行，不与价格混列 -->
        <el-table-column :label="auth.isAdmin() ? '实际销售保费' : '保费'" width="130">
          <template #default="{ row }">
            <div>{{ money(row.sale_total ?? row.premium) }}</div>
            <template v-if="auth.isAdmin()">
              <div class="muted">底价 {{ money(row.policy_floor_total) }}</div>
              <div class="muted">最低 {{ money(row.minimum_sale_total) }}</div>
            </template>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin()" label="总返佣金额" width="130">
          <template #default="{ row }">
            <div>{{ money(row.total_commission_total) }}</div>
            <div class="muted">业务员 {{ money(row.agent_commission_total) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态/保单文件" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status }}</el-tag>
            <!-- 文件名可能很长，单行省略，完整名称靠 title 悬浮提示 -->
            <div class="doc-line" :title="row.document_name || ''">
              <el-button v-if="row.document_download_url" link type="primary" size="small" @click="downloadDocument(row)">{{ row.document_name || '已导入' }}</el-button>
              <span v-else class="muted">未导入</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看计价</el-button>
            <el-button link type="primary" size="small" @click="exportOne(row)">导出明细</el-button>
            <el-button link type="primary" size="small" @click="openCertificate(row)">打印证明</el-button>
            <!-- 导入保单是低频一次性操作，收进「更多」保持直显 ≤3 个，避免按钮被列宽裁掉；
                 隐藏的 file input 留在单元格里（dropdown 菜单被 teleport，ref 不稳定） -->
            <template v-if="auth.isAdmin()">
              <input :ref="(el) => (fileInputs[row.id] = el as HTMLInputElement)" type="file" accept=".pdf,.jpg,.jpeg,.png" style="display: none" @change="(e) => onDocumentChange(row, e)" />
              <el-dropdown class="op-more" trigger="click">
                <el-button link type="primary" size="small">更多<el-icon class="el-icon--right"><arrow-down /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item :disabled="uploadingId === row.id" @click="triggerUpload(row)">导入保单</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <DetailModal v-model="detailVisible" title="保单详情">
      <template v-if="detailItem">
        <p>{{ detailItem.enterprise_name }} · {{ detailItem.insurer }} · {{ detailItem.plan_name }}</p>
        <template v-if="auth.isAdmin()">
          <p>保险原价：{{ money(detailItem.insurance_base_total) }}</p>
          <p>保司结算底价：{{ money(detailItem.policy_floor_total) }}</p>
          <p>销售最低价：{{ money(detailItem.minimum_sale_total) }}</p>
          <p>实际销售保费：{{ money(detailItem.sale_total ?? detailItem.premium) }}</p>
          <p>总返佣金额：{{ money(detailItem.total_commission_total) }}</p>
          <p>业务员佣金：{{ money(detailItem.agent_commission_total) }}</p>
        </template>
        <p v-else>保费：{{ money(detailItem.sale_total ?? detailItem.premium) }}</p>
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
  font-size: 12px;
}
/* 保单文件名挤在状态标签下方，超长单行省略，完整名称靠 title 悬浮提示 */
.doc-line {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 2px;
}
.doc-line :deep(.el-button) {
  max-width: 100%;
}
.doc-line :deep(.el-button > span) {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.op-more {
  margin-left: 12px;
  vertical-align: middle;
}
</style>
