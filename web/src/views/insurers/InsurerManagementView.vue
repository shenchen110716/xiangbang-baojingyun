<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as insurersApi from '@/api/insurers'
import type { InsurerAccount, InsurerMonthlySettlementRow } from '@/api/insurers'
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
const form = reactive({ name: '', contact: '', phone: '', credit_code: '', email: '', address: '' })
function resetForm() {
  editingId.value = null
  Object.assign(form, { name: '', contact: '', phone: '', credit_code: '', email: '', address: '' })
}
function editInsurer(item: Insurer) {
  editingId.value = item.id
  Object.assign(form, { name: item.name, contact: item.contact, phone: item.phone, credit_code: item.credit_code, email: item.email, address: item.address })
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

const accountsVisible = ref(false)
const accountsTarget = ref<Insurer | null>(null)
const accounts = ref<InsurerAccount[]>([])
const accountsLoading = ref(false)
const accountForm = reactive({ username: '', password: '', name: '' })
async function openAccounts(item: Insurer) {
  accountsTarget.value = item
  Object.assign(accountForm, { username: '', password: '', name: '' })
  accountsVisible.value = true
  accountsLoading.value = true
  try {
    accounts.value = await insurersApi.listInsurerAccounts(item.id)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    accountsLoading.value = false
  }
}
const accountSaving = ref(false)
async function submitAccount() {
  if (!accountsTarget.value) return
  if (!accountForm.username.trim()) { ElMessage.error('请填写登录账号'); return }
  if (accountForm.password.length < 6) { ElMessage.error('密码至少 6 位'); return }
  accountSaving.value = true
  try {
    await insurersApi.createInsurerAccount(accountsTarget.value.id, accountForm)
    ElMessage.success('已创建登录账号')
    Object.assign(accountForm, { username: '', password: '', name: '' })
    accounts.value = await insurersApi.listInsurerAccounts(accountsTarget.value.id)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    accountSaving.value = false
  }
}
async function toggleAccountStatus(item: InsurerAccount) {
  if (!accountsTarget.value) return
  try {
    await insurersApi.setInsurerAccountStatus(item.id, item.status === 'active' ? 'paused' : 'active')
    ElMessage.success('已更新')
    accounts.value = await insurersApi.listInsurerAccounts(accountsTarget.value.id)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function resetAccountPassword(item: InsurerAccount) {
  try {
    const { value } = await ElMessageBox.prompt(`为账号「${item.username}」设置新密码（至少 6 位）`, '重置密码', {
      inputPattern: /^.{6,}$/, inputErrorMessage: '密码至少 6 位', inputType: 'password',
    })
    await insurersApi.resetInsurerAccountPassword(item.id, value)
    ElMessage.success('密码已重置，该账号需用新密码重新登录')
  } catch { /* cancelled */ }
}

const settlementVisible = ref(false)
const settlementTarget = ref<Insurer | null>(null)
const settlementRows = ref<InsurerMonthlySettlementRow[]>([])
const settlementLoading = ref(false)
async function openSettlement(item: Insurer) {
  settlementTarget.value = item
  settlementVisible.value = true
  settlementLoading.value = true
  try {
    settlementRows.value = await insurersApi.getInsurerMonthlySettlement(item.id)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    settlementLoading.value = false
  }
}
async function toggleSettlement(row: InsurerMonthlySettlementRow) {
  if (!settlementTarget.value) return
  try {
    if (row.settled) {
      await ElMessageBox.confirm(`确认取消「${row.month}」的结算标记？`, '取消结算', { type: 'warning' })
      await insurersApi.unmarkInsurerMonthSettled(settlementTarget.value.id, row.month)
      ElMessage.success('已取消结算标记')
    } else {
      const { value } = await ElMessageBox.prompt(`标记「${row.month}」为已结算，可填写备注（选填）`, '标记已结算')
      await insurersApi.markInsurerMonthSettled(settlementTarget.value.id, row.month, value || '')
      ElMessage.success('已标记结算')
    }
    settlementRows.value = await insurersApi.getInsurerMonthlySettlement(settlementTarget.value.id)
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message)
  }
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
        <el-form-item label="统一社会信用代码"><el-input v-model="form.credit_code" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
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
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="editInsurer(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openAccounts(row)">登录账号</el-button>
            <el-button link type="primary" size="small" @click="openSettlement(row)">月度结算</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <PageCard v-if="pendingEdits.length" title="待审核的保司信息变更" :count="pendingEdits.length">
      <el-table :data="pendingEdits" size="small">
        <el-table-column label="当前名称" min-width="140"><template #default="{ row }">{{ row.name }}</template></el-table-column>
        <el-table-column label="申请修改为" min-width="220">
          <template #default="{ row }">
            <div>{{ row.pending_name || row.name }}</div>
            <small class="muted">{{ row.pending_contact || row.contact }} · {{ row.pending_phone || row.phone }} · {{ row.pending_email || row.email || '无邮箱' }}</small>
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

    <el-dialog v-model="accountsVisible" :title="`${accountsTarget?.name || ''} · 登录账号`" width="600px">
      <el-table v-loading="accountsLoading" :data="accounts" size="small" style="margin-bottom: 18px">
        <el-table-column prop="username" label="账号" min-width="120" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '启用' : '暂停' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="toggleAccountStatus(row)">{{ row.status === 'active' ? '暂停' : '启用' }}</el-button>
            <el-button link type="primary" size="small" @click="resetAccountPassword(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!accountsLoading && !accounts.length" description="暂无登录账号" :image-size="50" />
      <el-form :model="accountForm" label-width="100px">
        <el-form-item label="登录账号" required><el-input v-model="accountForm.username" placeholder="用于登录保司端" /></el-form-item>
        <el-form-item label="登录密码" required><el-input v-model="accountForm.password" type="password" show-password placeholder="至少 6 位" /></el-form-item>
        <el-form-item label="账号名称"><el-input v-model="accountForm.name" placeholder="留空则使用保司名称" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="accountsVisible = false">关闭</el-button>
        <el-button type="primary" :loading="accountSaving" @click="submitAccount">创建账号</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="settlementVisible" :title="`${settlementTarget?.name || ''} · 按月应收总保费结算`" width="640px">
      <el-table v-loading="settlementLoading" :data="settlementRows" size="small">
        <el-table-column prop="month" label="月份" width="100" />
        <el-table-column label="应收保费" width="120">
          <template #default="{ row }">{{ row.total_premium.toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="insured_count" label="参保人数" width="90" />
        <el-table-column label="是否已结算" width="100">
          <template #default="{ row }"><el-tag size="small" :type="row.settled ? 'success' : 'info'">{{ row.settled ? '已结算' : '未结算' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="结算时间" min-width="140">
          <template #default="{ row }">{{ row.settled_at ? row.settled_at.replace('T', ' ').slice(0, 19) : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="toggleSettlement(row)">{{ row.settled ? '取消结算' : '标记结算' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!settlementLoading && !settlementRows.length" description="暂无保费记录" :image-size="50" />
      <template #footer>
        <el-button @click="settlementVisible = false">关闭</el-button>
      </template>
    </el-dialog>

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
