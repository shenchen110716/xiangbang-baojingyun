<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listInsured, setInsuredStatus, updateInsured } from '@/api/insured'
import type { InsuredPerson } from '@/api/types'
import { formatCoverageDate, formatDateTime, insuredStatusLabel } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'
import { downloadCsv } from '@/utils/download'
import EmployeeDetailDialog from './EmployeeDetailDialog.vue'
import EmployeeEditorDialog from './EmployeeEditorDialog.vue'

const loading = ref(true)
const list = ref<InsuredPerson[]>([])
const search = ref('')
const searchField = ref<'all' | 'name' | 'id_number' | 'actual_employer_name'>('all')
const statusFilter = ref('')
const planFilter = ref<number | null>(null)
const selected = ref<InsuredPerson[]>([])
const bulkAction = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await listInsured()
  } finally {
    loading.value = false
  }
}
onMounted(load)

function isPendingEffective(x: InsuredPerson) {
  return x.status === 'active' && !!x.effective_at && new Date(x.effective_at) > new Date()
}

const filtered = computed(() => {
  let rows = list.value
  // “待生效”对外统一成一个筛选项：待审核(pending) + 已通过但未来才生效(active 但
  // effective_at 还没到)。这两种状态在列表的状态标签上本来就显示同一个“待生效”文案
  // （见 insuredStatusLabel），筛选下拉框以前拆成两个同名不同值的选项，看着像重复。
  if (statusFilter.value === 'pending') rows = rows.filter((x) => x.status === 'pending' || isPendingEffective(x))
  else if (statusFilter.value === 'active') rows = rows.filter((x) => x.status === 'active' && !isPendingEffective(x))
  else if (statusFilter.value) rows = rows.filter((x) => x.status === statusFilter.value)
  if (planFilter.value) rows = rows.filter((x) => x.plan_id === planFilter.value)
  if (search.value) {
    const q = search.value.toLowerCase()
    const fields = searchField.value === 'all' ? (['name', 'id_number', 'phone', 'enterprise_name', 'position_name'] as const) : ([searchField.value] as const)
    rows = rows.filter((x) => fields.some((f) => (x[f as keyof InsuredPerson] as string || '').toLowerCase().includes(q)))
  }
  return rows
})
const { page, pageSize, total: pagedTotal, paged } = usePagedList(filtered)

const planOptions = computed(() => {
  const seen = new Map<number, string>()
  for (const x of list.value) if (x.plan_id && !seen.has(x.plan_id)) seen.set(x.plan_id, x.plan_name || `方案 #${x.plan_id}`)
  return Array.from(seen, ([id, name]) => ({ id, name }))
})

const totalCount = computed(() => list.value.length)
// 在保 = 已生效的 active；待生效 = 待审核(pending) + 已通过但未来才生效(active-pending)。
// 修复：原来把 active-pending 计入在保、待生效恒为 0（保经云问题 7.18 第 7 条）。
const activeCount = computed(() => list.value.filter((x) => x.status === 'active' && !isPendingEffective(x)).length)
const pendingCount = computed(() => list.value.filter((x) => x.status === 'pending' || isPendingEffective(x)).length)
const stoppedCount = computed(() => list.value.filter((x) => x.status === 'stopped').length)

// ---- detail / edit dialogs ----
const detailVisible = ref(false)
const editorVisible = ref(false)
const activePerson = ref<InsuredPerson | null>(null)

function openDetail(item: InsuredPerson) {
  activePerson.value = item
  detailVisible.value = true
}
function openCertificate(item: InsuredPerson) {
  window.open(`/certificate/person/${item.id}`, '_blank')
}
// 批量导出勾选员工的参保证明：之前只能一个个点"参保证明"单独导出，一次导出
// 一整个企业（比如"三只松鼠"）几十上百人要点几十上百次——复用列表已有的多选
// 勾选框，:id 传逗号拼接的员工 id 列表给 CertificateView 的 selection 模式。
function openBatchCertificate() {
  if (!selected.value.length) { ElMessage.error('请先勾选员工'); return }
  const ids = selected.value.map((p) => p.id).join(',')
  window.open(`/certificate/selection/${ids}`, '_blank')
}

// ---- 创建投保证明：按月份/派遣单位/姓名查询候选人员，再用穿梭框手工挑选
// 具体生成证明的名单（用户反馈 2026-07-29，按参考截图的交互）。跟上面
// "勾选后批量导出"是两条互补路径：这条路径不用先在几百行的表格里翻页找人，
// 查询条件本身就是筛选器；表格勾选路径留给已经在列表里定位到具体人的场景。
const createCertVisible = ref(false)
const createCertMonth = ref('')
const createCertEmployerName = ref('')
const createCertPersonName = ref('')
const createCertIncludeInactive = ref(false)
const createCertCandidates = ref<InsuredPerson[]>([])
const createCertTargetKeys = ref<number[]>([])
const createCertQueried = ref(false)

const createCertEmployerOptions = computed(() => {
  const names = new Set<string>()
  for (const x of list.value) if (x.actual_employer_name) names.add(x.actual_employer_name)
  return Array.from(names).sort()
})

function openCreateCertificate() {
  createCertVisible.value = true
  createCertMonth.value = ''
  createCertEmployerName.value = ''
  createCertPersonName.value = ''
  createCertIncludeInactive.value = false
  createCertCandidates.value = []
  createCertTargetKeys.value = []
  createCertQueried.value = false
}

function queryCreateCertCandidates() {
  if (!createCertMonth.value) { ElMessage.error('请先选择月份'); return }
  const [y, m] = createCertMonth.value.split('-').map(Number)
  const monthStart = new Date(y, m - 1, 1)
  const monthEnd = new Date(y, m, 1)
  let rows = list.value
  if (createCertEmployerName.value) rows = rows.filter((p) => p.actual_employer_name === createCertEmployerName.value)
  if (createCertPersonName.value.trim()) {
    const q = createCertPersonName.value.trim()
    rows = rows.filter((p) => p.name.includes(q))
  }
  if (!createCertIncludeInactive.value) {
    // 默认只保留查询月份里有过在保区间（哪怕只覆盖一天）的人；勾选"是否包含
    // 不在保"后才把整个月都没有在保区间的人也纳入候选（比如查更早离职的人）。
    rows = rows.filter((p) => {
      if (!p.effective_at) return false
      const effective = new Date(p.effective_at)
      const terminated = p.terminated_at ? new Date(p.terminated_at) : null
      return effective < monthEnd && (terminated === null || terminated > monthStart)
    })
  }
  createCertCandidates.value = rows
  createCertTargetKeys.value = []
  createCertQueried.value = true
}

function resetCreateCertQuery() {
  createCertMonth.value = ''
  createCertEmployerName.value = ''
  createCertPersonName.value = ''
  createCertIncludeInactive.value = false
  createCertCandidates.value = []
  createCertTargetKeys.value = []
  createCertQueried.value = false
}

const createCertTransferData = computed(() =>
  createCertCandidates.value.map((p) => ({
    key: p.id,
    label: `${p.name} · ${p.actual_employer_name || '无派遣单位'}${p.id_number ? ' · ' + p.id_number.slice(-4) : ''}`,
  })),
)

function submitCreateCertificate() {
  if (!createCertTargetKeys.value.length) { ElMessage.error('请至少选择一名人员'); return }
  const ids = createCertTargetKeys.value.join(',')
  window.open(`/certificate/selection/${ids}`, '_blank')
  createCertVisible.value = false
}
function openEditor(item: InsuredPerson | null) {
  activePerson.value = item
  editorVisible.value = true
}
function editFromDetail() {
  detailVisible.value = false
  editorVisible.value = true
}
async function toggleStatusFromDetail() {
  if (!activePerson.value) return
  detailVisible.value = false
  if (activePerson.value.status === 'active') openStopDialog(activePerson.value)
  else await changeStatus(activePerson.value, 'active')
}

async function changeStatus(item: InsuredPerson, target: 'active' | 'pending') {
  try {
    await ElMessageBox.confirm(`确定将「${item.name}」${target === 'active' ? '参保' : '转为待生效'}吗？`, '操作确认', { type: 'warning' })
  } catch { return }
  try {
    await setInsuredStatus(item.id, target)
    ElMessage.success('操作成功')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function defaultStopDate() {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  return d.toISOString().slice(0, 10)
}

// ---- stop-insurance dialog: 停保必须选择停保时间，不能一键直接停保 ----
const stopVisible = ref(false)
const stopDate = ref('')
const stopTargets = ref<InsuredPerson[]>([])
const stopSaving = ref(false)
function openStopDialog(item: InsuredPerson) {
  stopTargets.value = [item]
  stopDate.value = defaultStopDate()
  stopVisible.value = true
}
function openBulkStopDialog() {
  if (!selected.value.length) { ElMessage.error('请先勾选员工'); return }
  stopTargets.value = selected.value
  stopDate.value = defaultStopDate()
  stopVisible.value = true
}
async function submitStop() {
  if (!stopDate.value) { ElMessage.error('请选择停保时间'); return }
  stopSaving.value = true
  try {
    await Promise.all(stopTargets.value.map((p) => updateInsured(p.id, { terminated_at: stopDate.value })))
    ElMessage.success(`已停保 ${stopTargets.value.length} 人`)
    stopVisible.value = false
    bulkAction.value = ''
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    stopSaving.value = false
  }
}

async function runBulkAction() {
  if (!bulkAction.value || !selected.value.length) { ElMessage.error('请勾选员工并选择操作'); return }
  if (bulkAction.value === 'stopped') { openBulkStopDialog(); return }
  try {
    await ElMessageBox.confirm(`确定对选中的 ${selected.value.length} 名员工执行该操作吗？`, '批量操作确认', { type: 'warning' })
  } catch { return }
  try {
    await Promise.all(selected.value.map((p) => setInsuredStatus(p.id, bulkAction.value as 'active' | 'pending')))
    ElMessage.success('批量操作完成')
    bulkAction.value = ''
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function exportCsv() {
  const header = ['姓名', '身份证号', '手机号', '投保单位', '实际工作单位', '岗位', '职业类别', '产品方案', '保单号', '状态', '添加时间', '生效时间', '停保时间']
  const rows = filtered.value.map((p) => [
    p.name, p.id_number, p.phone, p.enterprise_name, p.actual_employer_name, p.position_name, p.occupation_class, p.plan_name, p.policy_no, insuredStatusLabel(p).text,
    formatDateTime(p.created_at), formatCoverageDate(p.effective_at, p.effective_mode), formatCoverageDate(p.terminated_at, p.effective_mode),
  ])
  downloadCsv([header, ...rows], `响帮帮无忧保-员工-${Date.now()}.csv`)
}
</script>

<template>
  <div v-loading="loading" class="workers-view">
    <div class="stat-grid">
      <StatTile label="员工总数" :value="totalCount" />
      <StatTile label="在保" :value="activeCount" hint-type="success" />
      <StatTile label="待生效" :value="pendingCount" hint-type="warning" />
      <StatTile label="已停保" :value="stoppedCount" hint-type="danger" />
    </div>

    <PageCard title="参保员工列表" :count="filtered.length" hint="添加时间为手工新增保存或批量导入完成时系统自动记录的时间，与生效时间相互独立">
      <template #actions>
        <el-button @click="exportCsv">导出员工</el-button>
        <el-button type="primary" @click="openEditor(null)">＋ 新增参保员工</el-button>
      </template>
      <div class="filter-row">
        <FilterBar v-model:search="search" :placeholder="{ all: '搜索姓名/身份证号/手机号/单位', name: '按姓名搜索', id_number: '按身份证号搜索', actual_employer_name: '按实际单位搜索' }[searchField]">
          <el-select v-model="searchField" style="width: 130px">
            <el-option label="全部字段" value="all" />
            <el-option label="姓名" value="name" />
            <el-option label="身份证号" value="id_number" />
            <el-option label="实际单位" value="actual_employer_name" />
          </el-select>
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px">
            <el-option label="待生效" value="pending" />
            <el-option label="在保" value="active" />
            <el-option label="已停保" value="stopped" />
          </el-select>
          <el-select v-model="planFilter" placeholder="按参保方案筛选" clearable filterable style="width: 180px">
            <el-option v-for="p in planOptions" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </FilterBar>
        <div class="bulk-row">
          <el-select v-model="bulkAction" placeholder="批量操作" style="width: 150px">
            <el-option label="批量参保" value="active" />
            <el-option label="批量停保" value="stopped" />
            <el-option label="批量转待生效" value="pending" />
          </el-select>
          <el-button @click="runBulkAction">执行勾选操作</el-button>
          <el-button @click="openBatchCertificate">批量导出参保证明（{{ selected.length }}）</el-button>
          <el-button type="primary" plain @click="openCreateCertificate">创建投保证明</el-button>
        </div>
      </div>
      <el-table :data="paged" size="small" max-height="560" @selection-change="(rows: InsuredPerson[]) => (selected = rows)">
        <el-table-column type="selection" width="42" />
        <el-table-column label="被保险人" min-width="120">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <small class="muted">{{ row.phone || '—' }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="id_number" label="身份证号" width="180" />
        <el-table-column prop="enterprise_name" label="投保单位" min-width="130" />
        <el-table-column prop="actual_employer_name" label="实际工作单位" min-width="130" />
        <el-table-column label="岗位/类别" min-width="120">
          <template #default="{ row }">{{ row.position_name || row.occupation }} · {{ row.occupation_class }}</template>
        </el-table-column>
        <el-table-column label="保险方案/保单" min-width="150">
          <template #default="{ row }">
            <div>{{ row.plan_name || '未绑定' }}</div>
            <small class="muted">{{ row.policy_no || '尚未出单' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="insuredStatusLabel(row).type">{{ insuredStatusLabel(row).text }}</el-tag></template>
        </el-table-column>
        <!-- 生效/停保时间前移到保司标注前面，操作模块留在最后——生效/停保时间
             是查这张表时最常用的信息，保司标注/添加时间更多是辅助信息，尽量把
             重要信息往前放（用户反馈 2026-07-30 第 3 条）。 -->
        <el-table-column label="生效时间" width="150">
          <template #default="{ row }">{{ formatCoverageDate(row.effective_at, row.effective_mode) }}</template>
        </el-table-column>
        <el-table-column label="停保时间" width="150">
          <template #default="{ row }">{{ formatCoverageDate(row.terminated_at, row.effective_mode) }}</template>
        </el-table-column>
        <el-table-column label="保司标注" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.insurer_flag_reason" type="danger" size="small">{{ row.insurer_flag_reason }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="添加时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <!-- 参保/停保是最常用的操作，用实心按钮加大字号突出显示，避免和查看/编辑等
                 次要操作一样细小、难点中；查看/编辑/参保证明保留 link 样式，视觉上分层。 -->
            <el-button v-if="row.status === 'active'" type="danger" class="primary-action-btn" @click="openStopDialog(row)">停保</el-button>
            <el-button v-else type="success" class="primary-action-btn" @click="changeStatus(row, 'active')">参保</el-button>
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" @click="openEditor(row)">编辑</el-button>
            <el-button v-if="row.effective_at" link type="primary" @click="openCertificate(row)">参保证明</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <EmployeeDetailDialog v-model="detailVisible" :person="activePerson" @edit="editFromDetail" @toggle-status="toggleStatusFromDetail" />
    <EmployeeEditorDialog v-model="editorVisible" :person="activePerson" @saved="load" />

    <el-dialog v-model="stopVisible" title="选择停保时间" width="400px">
      <p class="dialog-hint">{{ stopTargets.length > 1 ? `将对选中的 ${stopTargets.length} 名员工统一停保` : `确定将「${stopTargets[0]?.name}」停保` }}</p>
      <el-form label-width="90px">
        <el-form-item label="停保时间"><el-date-picker v-model="stopDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stopVisible = false">取消</el-button>
        <el-button type="danger" :loading="stopSaving" @click="submitStop">确认停保</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createCertVisible" title="创建投保证明" width="900px">
      <el-form :inline="true" class="cert-query-form">
        <el-form-item label="月份" required>
          <el-date-picker v-model="createCertMonth" type="month" value-format="YYYY-MM" placeholder="请选择月份" style="width: 160px" />
        </el-form-item>
        <el-form-item label="派遣单位(可选)">
          <el-select v-model="createCertEmployerName" clearable filterable placeholder="不限" style="width: 180px">
            <el-option v-for="name in createCertEmployerOptions" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="姓名(可选)">
          <el-input v-model="createCertPersonName" clearable placeholder="支持模糊查询" style="width: 140px" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="createCertIncludeInactive">是否包含不在保</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="queryCreateCertCandidates">查询人员</el-button>
          <el-button @click="resetCreateCertQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <el-transfer
        v-model="createCertTargetKeys"
        :data="createCertTransferData"
        filterable
        filter-placeholder="请输入搜索内容"
        :titles="['可用人员', '生成证明的人员']"
        class="cert-transfer"
      />
      <p v-if="createCertQueried && !createCertCandidates.length" class="dialog-hint">没有符合条件的人员，换个查询条件试试。</p>
      <template #footer>
        <el-button @click="createCertVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreateCertificate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.workers-view {
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
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.bulk-row {
  display: flex;
  gap: 8px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.primary-action-btn {
  font-size: 14px;
  font-weight: 600;
  padding: 6px 14px;
  margin-right: 4px;
}
.dialog-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin: 4px 0 12px;
}
.cert-query-form {
  margin-bottom: 8px;
}
.cert-transfer {
  display: flex;
  justify-content: center;
}
</style>
