<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listInsured, setInsuredStatus } from '@/api/insured'
import type { InsuredPerson } from '@/api/types'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'
import EmployeeDetailDialog from './EmployeeDetailDialog.vue'
import EmployeeEditorDialog from './EmployeeEditorDialog.vue'

const loading = ref(true)
const list = ref<InsuredPerson[]>([])
const search = ref('')
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

const filtered = computed(() => {
  let rows = list.value
  if (statusFilter.value) rows = rows.filter((x) => x.status === statusFilter.value)
  if (search.value) {
    const q = search.value.toLowerCase()
    rows = rows.filter((x) => [x.name, x.id_number, x.phone, x.enterprise_name, x.position_name].some((v) => (v || '').toLowerCase().includes(q)))
  }
  return rows
})

const statusText: Record<string, string> = { active: '在保', pending: '待审核', stopped: '已停保' }
const statusType: Record<string, string> = { active: 'success', pending: 'warning', stopped: 'danger' }

const totalCount = computed(() => list.value.length)
const activeCount = computed(() => list.value.filter((x) => x.status === 'active').length)
const pendingCount = computed(() => list.value.filter((x) => x.status === 'pending').length)
const stoppedCount = computed(() => list.value.filter((x) => x.status === 'stopped').length)

// ---- detail / edit dialogs ----
const detailVisible = ref(false)
const editorVisible = ref(false)
const activePerson = ref<InsuredPerson | null>(null)

function openDetail(item: InsuredPerson) {
  activePerson.value = item
  detailVisible.value = true
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
  await changeStatus(activePerson.value, activePerson.value.status === 'active' ? 'stopped' : 'active')
}

async function changeStatus(item: InsuredPerson, target: 'active' | 'stopped' | 'pending') {
  try {
    await ElMessageBox.confirm(`确定将「${item.name}」${target === 'active' ? '参保' : target === 'stopped' ? '停保' : '转为待审核'}吗？`, '操作确认', { type: 'warning' })
  } catch { return }
  try {
    await setInsuredStatus(item.id, target)
    ElMessage.success('操作成功')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function runBulkAction() {
  if (!bulkAction.value || !selected.value.length) { ElMessage.error('请勾选员工并选择操作'); return }
  try {
    await ElMessageBox.confirm(`确定对选中的 ${selected.value.length} 名员工执行该操作吗？`, '批量操作确认', { type: 'warning' })
  } catch { return }
  try {
    await Promise.all(selected.value.map((p) => setInsuredStatus(p.id, bulkAction.value as 'active' | 'stopped' | 'pending')))
    ElMessage.success('批量操作完成')
    bulkAction.value = ''
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function exportCsv() {
  const header = ['姓名', '身份证号', '手机号', '投保单位', '实际工作单位', '岗位', '职业类别', '产品方案', '保单号', '状态']
  const rows = filtered.value.map((p) => [p.name, p.id_number, p.phone, p.enterprise_name, p.actual_employer_name, p.position_name, p.occupation_class, p.plan_name, p.policy_no, statusText[p.status]])
  const csv = '﻿' + [header, ...rows].map((r) => r.map((v) => `"${(v || '').toString().replace(/"/g, '""')}"`).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `响帮帮保经云-员工-${Date.now()}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}
</script>

<template>
  <div v-loading="loading" class="workers-view">
    <div class="stat-grid">
      <StatTile label="员工总数" :value="totalCount" />
      <StatTile label="在保" :value="activeCount" hint-type="success" />
      <StatTile label="待审核" :value="pendingCount" hint-type="warning" />
      <StatTile label="已停保" :value="stoppedCount" hint-type="danger" />
    </div>

    <PageCard title="参保员工列表" :count="filtered.length">
      <template #actions>
        <el-button @click="exportCsv">导出员工</el-button>
        <el-button type="primary" @click="openEditor(null)">＋ 新增参保员工</el-button>
      </template>
      <div class="filter-row">
        <FilterBar v-model:search="search">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px">
            <el-option label="待审核" value="pending" />
            <el-option label="在保" value="active" />
            <el-option label="已停保" value="stopped" />
          </el-select>
        </FilterBar>
        <div class="bulk-row">
          <el-select v-model="bulkAction" placeholder="批量操作" style="width: 150px">
            <el-option label="批量参保" value="active" />
            <el-option label="批量停保" value="stopped" />
            <el-option label="批量转待审核" value="pending" />
          </el-select>
          <el-button @click="runBulkAction">执行勾选操作</el-button>
        </div>
      </div>
      <el-table :data="filtered" size="small" @selection-change="(rows: InsuredPerson[]) => (selected = rows)">
        <el-table-column type="selection" width="42" />
        <el-table-column label="被保险人" min-width="140">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <small class="muted">{{ row.id_number }} · {{ row.phone }}</small>
          </template>
        </el-table-column>
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
          <template #default="{ row }"><el-tag size="small" :type="statusType[row.status]">{{ statusText[row.status] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" size="small" @click="openEditor(row)">编辑</el-button>
            <el-button link :type="row.status === 'active' ? 'danger' : 'success'" size="small" @click="changeStatus(row, row.status === 'active' ? 'stopped' : 'active')">
              {{ row.status === 'active' ? '停保' : '参保' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <EmployeeDetailDialog v-model="detailVisible" :person="activePerson" @edit="editFromDetail" @toggle-status="toggleStatusFromDetail" />
    <EmployeeEditorDialog v-model="editorVisible" :person="activePerson" @saved="load" />
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
</style>
