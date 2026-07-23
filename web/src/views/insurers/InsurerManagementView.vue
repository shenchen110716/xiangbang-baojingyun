<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as insurersApi from '@/api/insurers'
import type { Insurer } from '@/api/types'
import PageCard from '@/components/PageCard.vue'

const loading = ref(true)
const list = ref<Insurer[]>([])
const pendingEdits = ref<Insurer[]>([])

async function load() {
  loading.value = true
  try {
    const [all, pending] = await Promise.all([insurersApi.listInsurers(), insurersApi.listPendingInsurerEdits()])
    list.value = all
    pendingEdits.value = pending
  } finally {
    loading.value = false
  }
}
onMounted(load)

const editingId = ref<number | null>(null)
const form = reactive({ name: '', contact: '', phone: '' })
function resetForm() {
  editingId.value = null
  Object.assign(form, { name: '', contact: '', phone: '' })
}
function editInsurer(item: Insurer) {
  editingId.value = item.id
  Object.assign(form, { name: item.name, contact: item.contact, phone: item.phone })
}
const saving = ref(false)
async function submitForm() {
  if (!form.name.trim()) { ElMessage.error('请填写保险公司名称'); return }
  saving.value = true
  try {
    if (editingId.value) await insurersApi.updateInsurer(editingId.value, form)
    else await insurersApi.createInsurer(form)
    ElMessage.success(editingId.value ? '已保存' : '已创建')
    resetForm()
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function approveEdit(item: Insurer) {
  try {
    await ElMessageBox.confirm(`确认将「${item.name}」更新为「${item.pending_name}」？`, '审核确认', { type: 'warning' })
  } catch { return }
  await insurersApi.reviewInsurerEdit(item.id, { approve: true })
  ElMessage.success('已通过')
  load()
}
async function rejectEdit(item: Insurer) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回变更', { inputPattern: /.+/, inputErrorMessage: '请填写驳回原因' })
    await insurersApi.reviewInsurerEdit(item.id, { approve: false, reject_reason: value })
    ElMessage.success('已驳回')
    load()
  } catch { /* cancelled */ }
}

const mergeVisible = ref(false)
const mergeTarget = ref<number | null>(null)
const mergeSources = ref<number[]>([])
function openMerge() {
  mergeTarget.value = null
  mergeSources.value = []
  mergeVisible.value = true
}
const mergeCandidates = computed(() => list.value.filter((x) => x.id !== mergeTarget.value))
async function submitMerge() {
  if (!mergeTarget.value || !mergeSources.value.length) { ElMessage.error('请选择保留目标和待合并保司'); return }
  try {
    await ElMessageBox.confirm('合并后被合并保司名下的产品、账户绑定、保司账号都会改指到保留目标，且被合并记录会被删除，此操作不可逆。确认继续？', '合并确认', { type: 'warning' })
  } catch { return }
  try {
    await insurersApi.mergeInsurers({ source_ids: mergeSources.value, target_id: mergeTarget.value })
    ElMessage.success('已合并')
    mergeVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="insurer-management-view">
    <PageCard title="录入保险公司" hint="保司账号登录后只能看到、只能操作自己名下的数据，名称需与历史录入保持一致才能自动关联">
      <el-form :model="form" label-width="120px" class="insurer-form">
        <el-form-item label="保险公司名称" required><el-input v-model="form.name" placeholder="如：中国人保财险" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submitForm">{{ editingId ? '保存修改' : '保存' }}</el-button>
          <el-button v-if="editingId" @click="resetForm">取消编辑</el-button>
        </el-form-item>
      </el-form>
    </PageCard>

    <PageCard title="保险公司列表" :count="list.length">
      <template #actions>
        <el-button @click="openMerge">合并保司</el-button>
      </template>
      <el-table :data="list" size="small">
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="contact" label="联系人" width="120" />
        <el-table-column prop="phone" label="联系电话" width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '启用' : '暂停' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button link type="primary" size="small" @click="editInsurer(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </PageCard>

    <PageCard v-if="pendingEdits.length" title="待审核的保司信息变更" :count="pendingEdits.length">
      <el-table :data="pendingEdits" size="small">
        <el-table-column label="当前名称" min-width="140"><template #default="{ row }">{{ row.name }}</template></el-table-column>
        <el-table-column label="申请修改为" min-width="140">
          <template #default="{ row }">
            <div>{{ row.pending_name || row.name }}</div>
            <small class="muted">{{ row.pending_contact || row.contact }} · {{ row.pending_phone || row.phone }}</small>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="approveEdit(row)">通过</el-button>
            <el-button link type="danger" size="small" @click="rejectEdit(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <el-dialog v-model="mergeVisible" title="合并保司" width="480px">
      <el-form label-width="110px">
        <el-form-item label="保留目标">
          <el-select v-model="mergeTarget" placeholder="选择保留的保司" style="width: 100%">
            <el-option v-for="item in list" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="待合并">
          <el-select v-model="mergeSources" multiple placeholder="选择将被合并、删除的保司" style="width: 100%">
            <el-option v-for="item in mergeCandidates" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="danger" @click="submitMerge">确认合并</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.insurer-management-view {
  display: grid;
  gap: 18px;
}
.insurer-form {
  padding: 0 20px 20px;
  max-width: 480px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
