<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as enrollmentApi from '@/api/enrollment'
import { updatePlan } from '@/api/plans'
import { listEnterprises } from '@/api/enterprises'
import { listPositions } from '@/api/positions'
import { importInsuredFile, importTemplateUrl, listInsured } from '@/api/insured'
import type { Enterprise, EnrollmentEmailLog, EnrollmentSummaryRow, InsuredPerson, WorkPosition } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { downloadAuthenticated, downloadCsv } from '@/utils/download'
import { formatCoverageDate, formatDateTime, insuredStatusLabel } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()
const loading = ref(true)
const enterprises = ref<Enterprise[]>([])
const positions = ref<WorkPosition[]>([])
const summary = ref<EnrollmentSummaryRow[]>([])
const emails = ref<EnrollmentEmailLog[]>([])
const people = ref<InsuredPerson[]>([])
const today = new Date().toISOString().slice(0, 10)
const enrollDate = ref(today)

const approvedPositions = computed(() => positions.value.filter((x) => x.status === 'approved'))

async function load() {
  loading.value = true
  try {
    const [enterpriseList, positionList, summaryRows, emailRows, peopleRows] = await Promise.all([
      listEnterprises(),
      listPositions(),
      enrollmentApi.getEnrollmentSummary(enrollDate.value),
      enrollmentApi.listEnrollmentEmails(),
      listInsured(),
    ])
    enterprises.value = enterpriseList
    positions.value = positionList
    summary.value = summaryRows
    emails.value = emailRows
    people.value = peopleRows
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ---- 参停保人员名单 ----
const peopleSearch = ref('')
const peopleSearchField = ref<'all' | 'name' | 'id_number' | 'actual_employer_name'>('all')
const peopleStatusFilter = ref('')
function isPendingEffective(x: InsuredPerson) {
  return x.status === 'active' && !!x.effective_at && new Date(x.effective_at) > new Date()
}
const filteredPeople = computed(() => {
  let rows = people.value
  if (peopleStatusFilter.value === 'pending') rows = rows.filter((x) => x.status === 'pending' || isPendingEffective(x))
  else if (peopleStatusFilter.value === 'active') rows = rows.filter((x) => x.status === 'active' && !isPendingEffective(x))
  else if (peopleStatusFilter.value) rows = rows.filter((x) => x.status === peopleStatusFilter.value)
  if (peopleSearch.value) {
    const q = peopleSearch.value.toLowerCase()
    const fields = peopleSearchField.value === 'all' ? (['name', 'id_number', 'phone', 'enterprise_name', 'actual_employer_name'] as const) : ([peopleSearchField.value] as const)
    rows = rows.filter((x) => fields.some((f) => (x[f as keyof InsuredPerson] as string || '').toLowerCase().includes(q)))
  }
  return rows
})
const { page: peoplePage, pageSize: peoplePageSize, total: peoplePagedTotal, paged: pagedPeople } = usePagedList(filteredPeople)
const { page: emailsPage, pageSize: emailsPageSize, total: emailsPagedTotal, paged: pagedEmails } = usePagedList(computed(() => emails.value))
function exportPeopleCsv() {
  const header = ['姓名', '身份证号', '手机号', '投保单位', '实际工作单位', '岗位', '状态', '添加时间', '生效时间', '停保时间']
  const rows = filteredPeople.value.map((p) => [
    p.name, p.id_number, p.phone, p.enterprise_name, p.actual_employer_name, p.position_name, insuredStatusLabel(p).text,
    formatDateTime(p.created_at), formatCoverageDate(p.effective_at, p.effective_mode), formatCoverageDate(p.terminated_at, p.effective_mode),
  ])
  downloadCsv([header, ...rows], `响帮帮无忧保-参停保人员-${Date.now()}.csv`)
}

async function reloadSummary() {
  summary.value = await enrollmentApi.getEnrollmentSummary(enrollDate.value)
}

// ---- bulk import ----
const importForm = reactive({ kind: 'enrollment' as 'enrollment' | 'termination', enterprise_id: null as number | null, position_id: 0 })
const importFile = ref<File | null>(null)
const importError = ref('')
const importing = ref(false)
function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  importFile.value = input.files?.[0] || null
}
async function submitImport() {
  importError.value = ''
  if (!importForm.enterprise_id) { importError.value = '请选择投保单位'; return }
  if (importForm.kind === 'enrollment' && !importForm.position_id) { importError.value = '批量参保必须选择已审核岗位'; return }
  if (!importFile.value) { importError.value = '请选择要上传的文件'; return }
  importing.value = true
  try {
    const result = await importInsuredFile(importForm.kind, importForm.enterprise_id, importForm.position_id, importFile.value)
    if (!result.ok) {
      importError.value = result.errors.map((x: { row: number; message: string }) => `第 ${x.row} 行：${x.message}`).join('；')
      return
    }
    ElMessage.success(`${importForm.kind === 'enrollment' ? '参保' : '停保'}成功 ${result.success} 人`)
    importFile.value = null
    load()
  } catch (e) {
    importError.value = (e as Error).message
  } finally {
    importing.value = false
  }
}
async function downloadTemplate() {
  try {
    await downloadAuthenticated(importTemplateUrl().replace(/^\/api/, ''), '参保员工批量导入模板.xlsx')
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

// ---- export / email ----
async function exportList(kind: 'enrollment' | 'termination', planId: number) {
  try {
    await downloadAuthenticated(`/enrollment/export?kind=${kind}&date=${enrollDate.value}&plan_id=${planId}`, `${kind}-${enrollDate.value}.csv`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// 管理员按保司汇总【全部投保单位】一封发送；企业端只发本单位。
async function sendEmailFor(planId: number, kind: 'enrollment' | 'termination') {
  const label = kind === 'enrollment' ? '新参' : '停保'
  try {
    if (auth.isEnterprise()) {
      await ElMessageBox.confirm(`确认向保司邮箱发送本单位${label}名单附件？`, '发送确认', { type: 'warning' })
      const r = await enrollmentApi.emailEnrollment(auth.user!.enterprise_id!, planId, kind, enrollDate.value)
      ElMessage.success(`${label}名单已发送：${r.people_count} 人`)
    } else {
      await ElMessageBox.confirm(`确认按保司汇总【全部投保单位】的${label}名单，一次性发送一封邮件给保司？`, '汇总发送确认', { type: 'warning' })
      const r = await enrollmentApi.emailEnrollmentAggregate(planId, kind, enrollDate.value)
      ElMessage.success(`${label}汇总名单已发送：${r.people_count} 人，覆盖 ${r.unit_count} 家单位`)
    }
    load()
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message)
  }
}

// 内联编辑保司邮箱
const editingEmailPlanId = ref<number | null>(null)
const emailDraft = ref('')
function startEditEmail(row: { plan_id: number; insurer_email?: string }) {
  editingEmailPlanId.value = row.plan_id
  emailDraft.value = row.insurer_email || ''
}
async function saveEmail(planId: number) {
  try {
    await updatePlan(planId, { insurer_email: emailDraft.value.trim() })
    ElMessage.success('保司邮箱已更新')
    editingEmailPlanId.value = null
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// 人工标记保司回执
async function markReceipt(row: { id: number }) {
  try {
    const { value } = await ElMessageBox.prompt('确认后将标记该记录为「已确认回执」，可填写备注', '标记回执', {
      inputPlaceholder: '如：保司已确认收到名单', confirmButtonText: '标记已确认', cancelButtonText: '取消',
    })
    await enrollmentApi.markEnrollmentReceipt(row.id, 'confirmed', value || '')
    ElMessage.success('已标记回执')
    load()
  } catch { /* 取消 */ }
}
</script>

<template>
  <div v-loading="loading" class="enrollment-view">
    <PageCard title="批量参停保" hint="上传 CSV / XLSX 名单，批量执行参保或停保">
      <el-form :model="importForm" label-width="110px" class="import-form">
        <el-form-item label="业务类型">
          <el-select v-model="importForm.kind" style="width: 100%">
            <el-option label="批量参保" value="enrollment" />
            <el-option label="批量停保" value="termination" />
          </el-select>
        </el-form-item>
        <el-form-item label="投保单位">
          <el-select v-model="importForm.enterprise_id" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核岗位">
          <el-select v-model="importForm.position_id" :disabled="importForm.kind === 'termination'" style="width: 100%">
            <el-option label="停保无需岗位" :value="0" />
            <el-option v-for="p in approvedPositions" :key="p.id" :label="`${p.actual_employer_name || p.actual_employer} · ${p.name}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="CSV / XLSX 文件">
          <input type="file" accept=".csv,.xlsx" @change="onFileChange" />
        </el-form-item>
        <el-form-item class="wide">
          <el-button type="primary" :loading="importing" @click="submitImport">上传并执行</el-button>
          <el-button @click="downloadTemplate">下载模板</el-button>
        </el-form-item>
        <p class="hint-text wide">
          模板包含「投保单位」「实际工作单位」「岗位名称」三列（可留空，留空则使用上方选择的默认单位与岗位；填写后按名称匹配，可在一次导入中包含多个不同单位/岗位的名单，仅平台端可通过「投保单位」列导入其他单位数据），以及「生效日期」「停保日期」两列（格式 yyyy-MM-dd，可留空）：批量参保时填写生效日期会直接激活参保（不再停留在待生效）；批量停保时填写停保日期会用该日期而不是当前时间登记停保。
        </p>
        <p v-if="importError" class="error-text wide">{{ importError }}</p>
      </el-form>
    </PageCard>

    <PageCard title="参停保人员名单" :count="filteredPeople.length" hint="添加时间为手工新增保存或批量导入完成时系统自动记录的时间；生效时间和停保时间来自保障记录">
      <template #actions>
        <el-button @click="exportPeopleCsv">导出名单</el-button>
      </template>
      <div class="filter-row">
        <FilterBar v-model:search="peopleSearch">
          <el-select v-model="peopleSearchField" style="width: 130px">
            <el-option label="全部字段" value="all" />
            <el-option label="姓名" value="name" />
            <el-option label="身份证号" value="id_number" />
            <el-option label="实际单位" value="actual_employer_name" />
          </el-select>
          <el-select v-model="peopleStatusFilter" placeholder="全部状态" clearable style="width: 130px">
            <el-option label="待生效" value="pending" />
            <el-option label="在保" value="active" />
            <el-option label="已停保" value="stopped" />
          </el-select>
        </FilterBar>
      </div>
      <el-table :data="pagedPeople" size="small" max-height="560">
        <el-table-column label="被保险人" min-width="120">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <small class="muted">{{ row.id_number }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="120" />
        <el-table-column prop="enterprise_name" label="投保单位" min-width="120" />
        <el-table-column prop="actual_employer_name" label="实际工作单位" min-width="120" />
        <el-table-column prop="position_name" label="岗位" min-width="110" />
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
      </el-table>
      <TablePagination v-model:page="peoplePage" v-model:page-size="peoplePageSize" :total="peoplePagedTotal" />
    </PageCard>

    <PageCard title="产品参停保与邮件" :count="summary.length">
      <template #actions>
        <el-date-picker v-model="enrollDate" type="date" value-format="YYYY-MM-DD" style="width: 160px" @change="reloadSummary" />
      </template>
      <el-table :data="summary" size="small">
        <el-table-column label="保险公司 / 产品" min-width="150">
          <template #default="{ row }">
            <div><b>{{ row.insurer }}</b></div>
            <small class="muted">{{ row.product }}</small>
          </template>
        </el-table-column>
        <el-table-column label="保司邮箱" min-width="220">
          <template #default="{ row }">
            <div v-if="editingEmailPlanId === row.plan_id" class="email-edit">
              <el-input v-model="emailDraft" size="small" placeholder="保司接收邮箱" @keyup.enter="saveEmail(row.plan_id)" />
              <el-button link type="primary" size="small" @click="saveEmail(row.plan_id)">保存</el-button>
              <el-button link size="small" @click="editingEmailPlanId = null">取消</el-button>
            </div>
            <div v-else class="email-view">
              <span :class="{ muted: !row.insurer_email }">{{ row.insurer_email || '未设置' }}</span>
              <el-button v-if="!auth.isEnterprise()" link type="primary" size="small" @click="startEditEmail(row)">修改</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="insured_count" label="在保人数" width="90" />
        <el-table-column label="当日新参" width="90">
          <template #default="{ row }">
            <span :class="row.new_count > 0 ? 'count-hot' : 'count-zero'">{{ row.new_count }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当日停保" width="90">
          <template #default="{ row }">
            <span :class="row.stop_count > 0 ? 'count-stop' : 'count-zero'">{{ row.stop_count }}</span>
          </template>
        </el-table-column>
        <el-table-column label="导出" width="180">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="row.new_count === 0" @click="exportList('enrollment', row.plan_id)">新参名单</el-button>
            <el-button link type="primary" size="small" :disabled="row.stop_count === 0" @click="exportList('termination', row.plan_id)">停保名单</el-button>
          </template>
        </el-table-column>
        <el-table-column :label="auth.isEnterprise() ? '邮件发送' : '汇总发送'" min-width="170">
          <template #default="{ row }">
            <template v-if="row.insurer_email">
              <el-button link type="primary" size="small" :disabled="row.new_count === 0" @click="sendEmailFor(row.plan_id, 'enrollment')">邮件新参</el-button>
              <el-button link type="primary" size="small" :disabled="row.stop_count === 0" @click="sendEmailFor(row.plan_id, 'termination')">邮件停保</el-button>
            </template>
            <span v-else class="muted">请先设置保司邮箱</span>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <PageCard title="邮件发送记录" :count="emails.length" hint="含名单附件和请求编号">
      <el-table :data="pagedEmails" size="small">
        <el-table-column label="发送时间" width="150"><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></el-table-column>
        <el-table-column label="参停保日期" width="120"><template #default="{ row }">{{ row.data_date || '—' }}</template></el-table-column>
        <el-table-column prop="enterprise_name" label="投保单位" min-width="130" />
        <el-table-column label="保险公司 / 产品" min-width="150">
          <template #default="{ row }">
            <div>{{ row.insurer }}</div>
            <small class="muted">{{ row.plan_name }}</small>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80"><template #default="{ row }">{{ row.kind === 'enrollment' ? '新参' : '停保' }}</template></el-table-column>
        <el-table-column prop="recipient" label="收件邮箱" min-width="150" />
        <el-table-column label="人数 / 附件" min-width="150">
          <template #default="{ row }">
            <div>{{ row.people_count }}</div>
            <small class="muted">{{ row.filename }}</small>
          </template>
        </el-table-column>
        <el-table-column label="状态 / 请求编号" min-width="150">
          <template #default="{ row }">
            <div>{{ row.status === 'sent' ? '已发送' : row.status === 'failed' ? '发送失败' : row.status }}</div>
            <small class="muted">{{ row.request_id }}</small>
          </template>
        </el-table-column>
        <el-table-column label="对方回执" min-width="180">
          <template #default="{ row }">
            <div v-if="row.receipt_status === 'confirmed'">
              <el-tag type="success" size="small">已确认</el-tag>
              <div class="muted" style="font-size: 11.5px; margin-top: 2px">{{ formatDateTime(row.receipt_at) }}<span v-if="row.receipt_note"> · {{ row.receipt_note }}</span></div>
            </div>
            <div v-else>
              <el-tag type="info" size="small" effect="plain">待回执</el-tag>
              <el-button v-if="!auth.isEnterprise()" link type="primary" size="small" @click="markReceipt(row)">标记回执</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="emailsPage" v-model:page-size="emailsPageSize" :total="emailsPagedTotal" />
    </PageCard>
  </div>
</template>

<style scoped>
.enrollment-view {
  display: grid;
  gap: 18px;
}
/* 当日有新参/停保时醒目，为 0 时发灰 */
.count-hot {
  color: var(--el-color-primary);
  font-weight: 700;
}
.count-stop {
  color: var(--el-color-warning);
  font-weight: 700;
}
.count-zero {
  color: var(--el-text-color-placeholder);
}
.email-edit {
  display: flex;
  align-items: center;
  gap: 6px;
}
.import-form {
  padding: 0 20px 20px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.import-form :deep(.wide) {
  grid-column: 1 / -1;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.filter-row {
  padding: 0 20px 14px;
}
.error-text {
  color: var(--el-color-danger);
  font-size: 12px;
  margin: -8px 0 4px;
}
.hint-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: -8px 0 4px;
}
.link {
  color: var(--el-color-primary);
  font-size: 12px;
  text-decoration: none;
}
</style>
