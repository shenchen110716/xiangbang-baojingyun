<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as enrollmentApi from '@/api/enrollment'
import { listEnterprises } from '@/api/enterprises'
import { listPositions } from '@/api/positions'
import { importInsuredFile, importTemplateUrl } from '@/api/insured'
import type { Enterprise, EnrollmentEmailLog, EnrollmentSummaryRow, WorkPosition } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { downloadAuthenticated } from '@/utils/download'
import { formatDateTime } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'

const auth = useAuthStore()
const loading = ref(true)
const enterprises = ref<Enterprise[]>([])
const positions = ref<WorkPosition[]>([])
const summary = ref<EnrollmentSummaryRow[]>([])
const emails = ref<EnrollmentEmailLog[]>([])
const today = new Date().toISOString().slice(0, 10)
const enrollDate = ref(today)

const approvedPositions = computed(() => positions.value.filter((x) => x.status === 'approved'))

async function load() {
  loading.value = true
  try {
    const [enterpriseList, positionList, summaryRows, emailRows] = await Promise.all([
      listEnterprises(),
      listPositions(),
      enrollmentApi.getEnrollmentSummary(enrollDate.value),
      enrollmentApi.listEnrollmentEmails(),
    ])
    enterprises.value = enterpriseList
    positions.value = positionList
    summary.value = summaryRows
    emails.value = emailRows
  } finally {
    loading.value = false
  }
}
onMounted(load)

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
function downloadTemplate() {
  downloadAuthenticated(importTemplateUrl().replace(/^\/api/, ''), '参保员工批量导入模板.csv')
}

// ---- export / email ----
async function exportList(kind: 'enrollment' | 'termination', planId: number) {
  try {
    await downloadAuthenticated(`/enrollment/export?kind=${kind}&date=${enrollDate.value}&plan_id=${planId}`, `${kind}-${enrollDate.value}.csv`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const emailDialogVisible = ref(false)
const emailTarget = ref<{ planId: number; kind: 'enrollment' | 'termination' } | null>(null)
const emailEnterpriseId = ref<number | null>(null)
function openEmailDialog(planId: number, kind: 'enrollment' | 'termination') {
  if (auth.isEnterprise()) {
    sendEmail(planId, kind, auth.user!.enterprise_id!)
    return
  }
  emailTarget.value = { planId, kind }
  emailEnterpriseId.value = null
  emailDialogVisible.value = true
}
async function confirmEmailDialog() {
  if (!emailTarget.value || !emailEnterpriseId.value) { ElMessage.error('请选择投保单位'); return }
  await sendEmail(emailTarget.value.planId, emailTarget.value.kind, emailEnterpriseId.value)
  emailDialogVisible.value = false
}
async function sendEmail(planId: number, kind: 'enrollment' | 'termination', enterpriseId: number) {
  try {
    await ElMessageBox.confirm(`确认向保司邮箱发送${kind === 'enrollment' ? '新参' : '停保'}名单附件？`, '发送确认', { type: 'warning' })
  } catch { return }
  try {
    const result = await enrollmentApi.emailEnrollment(enterpriseId, planId, kind, enrollDate.value)
    ElMessage.success(`邮件已记录：${result.people_count} 人，${result.filename}`)
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
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
        <p v-if="importError" class="error-text wide">{{ importError }}</p>
      </el-form>
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
        <el-table-column label="保司邮箱" min-width="150">
          <template #default="{ row }">{{ row.insurer_email || '未设置' }}</template>
        </el-table-column>
        <el-table-column prop="insured_count" label="在保人数" width="90" />
        <el-table-column prop="new_count" label="当日新参" width="90" />
        <el-table-column prop="stop_count" label="当日停保" width="90" />
        <el-table-column label="导出" width="180">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="exportList('enrollment', row.plan_id)">新参名单</el-button>
            <el-button link type="primary" size="small" @click="exportList('termination', row.plan_id)">停保名单</el-button>
          </template>
        </el-table-column>
        <el-table-column label="邮件发送" min-width="160">
          <template #default="{ row }">
            <template v-if="row.insurer_email">
              <el-button link type="primary" size="small" @click="openEmailDialog(row.plan_id, 'enrollment')">邮件新参</el-button>
              <el-button link type="primary" size="small" @click="openEmailDialog(row.plan_id, 'termination')">邮件停保</el-button>
            </template>
            <router-link v-else :to="{ name: 'insurers' }" class="link">设置保司邮箱</router-link>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <PageCard title="邮件发送记录" :count="emails.length" hint="含名单附件和请求编号">
      <el-table :data="emails" size="small">
        <el-table-column label="发送时间" width="150"><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></el-table-column>
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
            <div>{{ row.status }}</div>
            <small class="muted">{{ row.request_id }}</small>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <el-dialog v-model="emailDialogVisible" title="选择投保单位" width="360px">
      <el-select v-model="emailEnterpriseId" placeholder="请选择投保单位" style="width: 100%">
        <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
      </el-select>
      <template #footer>
        <el-button @click="emailDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmEmailDialog">确认发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.enrollment-view {
  display: grid;
  gap: 18px;
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
.error-text {
  color: var(--el-color-danger);
  font-size: 12px;
  margin: -8px 0 4px;
}
.link {
  color: var(--el-color-primary);
  font-size: 12px;
  text-decoration: none;
}
</style>
