<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as positionsApi from '@/api/positions'
import { recognizeBusinessLicense } from '@/api/ocr'
import { isValidCreditCode } from '@/utils/creditCode'
import type { ActualEmployer } from '@/api/types'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

// enterpriseId 为空时是企业端自助管理（原有行为，归属单位由后端按登录账号的
// enterprise_id 解析）；总后台端从投保单位列表进来时会传具体的 enterpriseId，
// 表示"以平台身份管理这一家单位的实际用工单位"——之前总后台完全没有入口能
// 新增实际用工单位（用户反馈 2026-07-30 第 1 条）。
const props = defineProps<{ enterpriseId?: number | null }>()

const loading = ref(true)
const list = ref<ActualEmployer[]>([])
const search = ref('')

async function load() {
  loading.value = true
  try {
    const all = await positionsApi.listActualEmployers()
    list.value = props.enterpriseId ? all.filter((x) => x.enterprise_id === props.enterpriseId) : all
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  if (!search.value) return list.value
  const q = search.value.toLowerCase()
  return list.value.filter((x) => [x.name, x.credit_code, x.contact, x.phone].some((v) => v.toLowerCase().includes(q)))
})
const { page, pageSize, total: pagedTotal, paged } = usePagedList(filtered)
const activeCount = computed(() => list.value.filter((x) => x.status === 'active').length)
const pausedCount = computed(() => list.value.filter((x) => x.status === 'paused').length)

const formVisible = ref(false)
const editingId = ref<number | null>(null)
// enterprise_id 只有总后台端（有 enterpriseId prop）才用得到，传给后端指定归属
// 单位；企业端自己新增时后端按登录账号解析，这个字段会被忽略，留着不影响。
// 类型必须是 number | undefined（不能是 null）——ActualEmployer.enterprise_id
// 是 number，Partial<ActualEmployer> 只允许 number | undefined，传 null 会让
// vue-tsc 类型检查失败，导致 npm run build 整个失败、Render 部署被卡住（这才是
// 用户反馈"改了还是看不到"的真正原因：不是没推送，是构建从这里就没成功过）。
const form = reactive({ name: '', credit_code: '', contact: '', phone: '', enterprise_id: undefined as number | undefined })
// 营业执照 OCR 识别，跟 EnterprisesPanel.vue（投保单位新增）同一套能力，自动带出
// 单位名称/统一社会信用代码，减少手打出错——之前这里完全没有校验（用户反馈
// 2026-07-30 第 2 条）。
const ocrLoading = ref(false)
const ocrHint = ref('')
async function handleLicenseFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  ocrLoading.value = true
  ocrHint.value = ''
  try {
    const res = await recognizeBusinessLicense(file)
    form.name = res.name
    if (res.credit_code) form.credit_code = res.credit_code
    ocrHint.value = res.mock ? '模拟识别结果，请核对后再保存' : '已识别，请核对后再保存'
  } catch (e) {
    ElMessage.error((e as Error).message || '识别失败，请手动填写')
  } finally {
    ocrLoading.value = false
  }
}
const creditCodeInvalid = computed(() => !!form.credit_code && !isValidCreditCode(form.credit_code))

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', credit_code: '', contact: '', phone: '', enterprise_id: props.enterpriseId ?? undefined })
  ocrHint.value = ''
  formVisible.value = true
}
function openEdit(item: ActualEmployer) {
  editingId.value = item.id
  Object.assign(form, { name: item.name, credit_code: item.credit_code, contact: item.contact, phone: item.phone })
  ocrHint.value = ''
  formVisible.value = true
}
async function submitForm() {
  if (!form.name.trim() || form.name.trim().length < 2) { ElMessage.error('请填写完整的单位名称'); return }
  if (!form.credit_code.trim()) { ElMessage.error('请填写统一社会信用代码'); return }
  if (creditCodeInvalid.value) { ElMessage.error('统一社会信用代码格式或校验位不正确，请核对'); return }
  try {
    if (editingId.value) await positionsApi.updateActualEmployer(editingId.value, form)
    else await positionsApi.createActualEmployer(form)
    ElMessage.success('保存成功')
    formVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function toggleStatus(item: ActualEmployer) {
  const next = item.status === 'active' ? 'paused' : 'active'
  try {
    await positionsApi.setActualEmployerStatus(item.id, next)
    ElMessage.success(next === 'active' ? '已恢复使用' : '已暂停使用')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeItem(item: ActualEmployer) {
  try {
    await ElMessageBox.confirm(`确定删除工作单位「${item.name}」吗？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await positionsApi.deleteActualEmployer(item.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="work-units-panel">
    <div class="stat-grid">
      <StatTile label="工作单位总数" :value="list.length" />
      <StatTile label="正常使用" :value="activeCount" hint-type="success" />
      <StatTile label="已暂停" :value="pausedCount" hint-type="warning" />
    </div>

    <PageCard title="工作单位列表" :count="filtered.length">
      <template #actions>
        <el-button type="primary" @click="openCreate">＋ 新增实际工作单位</el-button>
      </template>
      <div class="filter-row"><FilterBar v-model:search="search" /></div>
      <el-table :data="paged" size="small">
        <el-table-column prop="name" label="单位名称" min-width="160" />
        <el-table-column prop="credit_code" label="统一社会信用代码" min-width="160" />
        <el-table-column prop="contact" label="联系人" width="110" />
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'danger'" size="small">
              {{ row.status === 'active' ? '正常' : '已暂停' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.has_active_people">
              <span class="muted">已有参保员工，不可编辑/删除</span>
            </template>
            <template v-else>
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="removeItem(row)">删除</el-button>
            </template>
            <el-button link type="primary" size="small" @click="toggleStatus(row)">{{ row.status === 'active' ? '暂停' : '恢复' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑工作单位' : '新增实际工作单位'" width="480px">
      <el-form :model="form" label-width="140px">
        <el-form-item v-if="!editingId" label="营业执照">
          <input type="file" accept="image/*" @change="handleLicenseFile" />
          <div v-if="ocrLoading" class="hint">正在识别…</div>
          <div v-else-if="ocrHint" class="hint" style="color: var(--el-color-success)">{{ ocrHint }}</div>
          <div v-else class="hint">上传营业执照照片可自动带出单位名称/统一社会信用代码，也可直接手工填写</div>
        </el-form-item>
        <el-form-item label="单位名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="统一社会信用代码" required>
          <div style="width: 100%">
            <el-input v-model="form.credit_code" :class="{ 'is-invalid-code': creditCodeInvalid }" />
            <div v-if="creditCodeInvalid" class="hint" style="color: var(--el-color-danger)">格式或校验位不正确，可能拍错/打错了，请核对</div>
          </div>
        </el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.work-units-panel {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.muted {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.filter-row {
  padding: 0 20px 14px;
}
.hint {
  display: block;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  margin-top: 4px;
}
.is-invalid-code :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
</style>
