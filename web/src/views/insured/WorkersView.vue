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
  if (statusFilter.value === 'active-pending') rows = rows.filter(isPendingEffective)
  else if (statusFilter.value === 'active') rows = rows.filter((x) => x.status === 'active' && !isPendingEffective(x))
  else if (statusFilter.value) rows = rows.filter((x) => x.status === statusFilter.value)
  if (search.value) {
    const q = search.value.toLowerCase()
    const fields = searchField.value === 'all' ? (['name', 'id_number', 'phone', 'enterprise_name', 'position_name'] as const) : ([searchField.value] as const)
    rows = rows.filter((x) => fields.some((f) => (x[f as keyof InsuredPerson] as string || '').toLowerCase().includes(q)))
  }
  return rows
})
const { page, pageSize, total: pagedTotal, paged } = usePagedList(filtered)

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
  downloadCsv([header, ...rows], `响帮帮保经云-员工-${Date.now()}.csv`)
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
            <el-option label="待生效(倒计时)" value="active-pending" />
            <el-option label="在保" value="active" />
            <el-option label="已停保" value="stopped" />
          </el-select>
        </FilterBar>
        <div class="bulk-row">
          <el-select v-model="bulkAction" placeholder="批量操作" style="width: 150px">
            <el-option label="批量参保" value="active" />
            <el-option label="批量停保" value="stopped" />
            <el-option label="批量转待生效" value="pending" />
          </el-select>
          <el-button @click="runBulkAction">执行勾选操作</el-button>
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
        <el-table-column label="添加时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="生效时间" width="150">
          <template #default="{ row }">{{ formatCoverageDate(row.effective_at, row.effective_mode) }}</template>
        </el-table-column>
        <el-table-column label="停保时间" width="150">
          <template #default="{ row }">{{ formatCoverageDate(row.terminated_at, row.effective_mode) }}</template>
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
</style>
