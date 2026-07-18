<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as operatorsApi from '@/api/operators'
import * as employerScopesApi from '@/api/employerScopes'
import { listEnterprises, createEnterpriseAdmin } from '@/api/enterprises'
import { listActualEmployers } from '@/api/positions'
import type { ActualEmployer, EmployerScope, Enterprise, Operator } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import PageCard from '@/components/PageCard.vue'
import StatTile from '@/components/StatTile.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()
const route = useRoute()
const loading = ref(true)
const list = ref<Operator[]>([])
// 统一「单位账号管理」：admin 可按投保单位筛选；支持 ?enterprise_id 直达（从投保单位管理跳转）。
const enterpriseFilter = ref<number | null>(route.query.enterprise_id ? Number(route.query.enterprise_id) : null)
const filteredList = computed(() => enterpriseFilter.value ? list.value.filter((x) => x.enterprise_id === enterpriseFilter.value) : list.value)
const enterprises = ref<Enterprise[]>([])
const employers = ref<ActualEmployer[]>([])
const scopes = ref<EmployerScope[]>([])

const canManage = computed(() => auth.isAdmin() || auth.isEnterpriseOwner())
const activeScopesByUser = computed(() => {
  const result = new Map<number, EmployerScope[]>()
  for (const scope of scopes.value) {
    if (scope.status !== 'active' || scope.revoked_at) continue
    result.set(scope.user_id, [...(result.get(scope.user_id) || []), scope])
  }
  return result
})
function activeScopes(item: Operator) {
  return activeScopesByUser.value.get(item.id) || []
}

async function load() {
  loading.value = true
  try {
    list.value = await operatorsApi.listOperators()
    if (canManage.value) {
      const [scopeRows, employerRows] = await Promise.all([
        employerScopesApi.listEmployerScopes(),
        listActualEmployers(),
      ])
      scopes.value = scopeRows
      employers.value = employerRows.filter((item) => item.status === 'active')
    } else {
      scopes.value = []
      employers.value = []
    }
    if (auth.isAdmin()) enterprises.value = await listEnterprises()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const { page, pageSize, total: pagedTotal, paged } = usePagedList(filteredList)
const activeCount = computed(() => filteredList.value.filter((x) => x.active).length)
const inactiveCount = computed(() => filteredList.value.filter((x) => !x.active).length)

const createVisible = ref(false)
const createForm = reactive({ enterprise_id: null as number | null, role: 'operator' as 'owner' | 'operator', name: '', username: '', password: '', phone: '' })
const createError = ref('')
const saving = ref(false)
// admin 才能建"单位主管"；企业主管本人只能建操作员。
const canCreateOwner = computed(() => auth.isAdmin())
function openCreate() {
  Object.assign(createForm, { enterprise_id: enterpriseFilter.value, role: 'operator', name: '', username: '', password: '', phone: '' })
  createError.value = ''
  createVisible.value = true
}
async function submitCreate() {
  createError.value = ''
  if (!createForm.name) { createError.value = '请输入姓名'; return }
  if (createForm.username.length < 3) { createError.value = '登录账号至少 3 位'; return }
  if (createForm.password.length < 6) { createError.value = '初始密码至少 6 位'; return }
  const enterpriseId = auth.isEnterprise() ? auth.user?.enterprise_id ?? null : createForm.enterprise_id
  if (!enterpriseId) { createError.value = '请先选择所属投保单位'; return }
  saving.value = true
  try {
    if (createForm.role === 'owner' && canCreateOwner.value) {
      await createEnterpriseAdmin(enterpriseId, { username: createForm.username, password: createForm.password, name: createForm.name, phone: createForm.phone })
    } else {
      await operatorsApi.createOperator({ username: createForm.username, password: createForm.password, name: createForm.name, phone: createForm.phone, enterprise_id: auth.isAdmin() ? enterpriseId : undefined })
    }
    ElMessage.success('账号已创建')
    createVisible.value = false
    load()
  } catch (e) {
    createError.value = (e as Error).message
  } finally {
    saving.value = false
  }
}

const editVisible = ref(false)
const editForm = reactive({ id: 0, username: '', name: '', phone: '', enterprise_id: null as number | null })
const editError = ref('')
const editSaving = ref(false)
const editTarget = ref<Operator | null>(null)
const canReassignEnterprise = computed(() => auth.isAdmin() && !editTarget.value?.is_owner)
// 无有效数据的账号允许平台端改登录账号（用户名）。
const canEditUsername = computed(() => auth.isAdmin() && !editTarget.value?.has_data)
function openEdit(item: Operator) {
  editTarget.value = item
  Object.assign(editForm, { id: item.id, username: item.username, name: item.name, phone: item.phone || '', enterprise_id: item.enterprise_id })
  editError.value = ''
  editVisible.value = true
}
async function submitEdit() {
  editError.value = ''
  if (!editForm.name.trim()) { editError.value = '请输入姓名'; return }
  if (canReassignEnterprise.value && !editForm.enterprise_id) { editError.value = '请选择所属投保单位'; return }
  editSaving.value = true
  try {
    const payload: { username?: string; name: string; phone: string; enterprise_id?: number } = { name: editForm.name.trim(), phone: editForm.phone.trim() }
    if (canReassignEnterprise.value && editForm.enterprise_id) payload.enterprise_id = editForm.enterprise_id
    if (canEditUsername.value && editForm.username.trim() && editForm.username.trim() !== editTarget.value?.username) payload.username = editForm.username.trim()
    await operatorsApi.updateOperator(editForm.id, payload)
    ElMessage.success('账号信息已更新')
    editVisible.value = false
    load()
  } catch (e) {
    editError.value = (e as Error).message
  } finally {
    editSaving.value = false
  }
}

async function removeOperator(item: Operator) {
  try {
    await ElMessageBox.confirm(`确定删除账号「${item.name}」（${item.username}）吗？删除后不可恢复。仅未产生业务数据的账号可删除。`, '删除账号', { type: 'warning' })
  } catch { return }
  try {
    await operatorsApi.deleteOperator(item.id)
    ElMessage.success('账号已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function toggleActive(item: Operator) {
  try {
    await operatorsApi.updateOperator(item.id, { active: !item.active })
    ElMessage.success(item.active ? '操作员已停用，其当前登录会话将立即失效' : '操作员已启用')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function resetPassword(item: Operator) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新密码（至少 6 位）。重置后该账号在所有设备上的登录会话将立即失效，需使用新密码重新登录。', '重置密码', { inputPattern: /.{6,}/, inputErrorMessage: '新密码至少 6 位' })
    await operatorsApi.updateOperator(item.id, { password: value })
    ElMessage.success('操作员密码已重置，原登录会话已失效')
  } catch { /* cancelled */ }
}

const scopeVisible = ref(false)
const scopeSaving = ref(false)
const scopeError = ref('')
const scopeTarget = ref<Operator | null>(null)
const scopeForm = reactive({ actual_employer_id: null as number | null, responsibility_type: 'collaborator' as 'primary' | 'collaborator' })
const assignableEmployers = computed(() => {
  if (!scopeTarget.value) return employers.value
  const assigned = new Set(activeScopes(scopeTarget.value).map((scope) => scope.actual_employer_id))
  return scopeForm.responsibility_type === 'primary'
    ? employers.value
    : employers.value.filter((employer) => !assigned.has(employer.id))
})
function openScopeDialog(item: Operator) {
  scopeTarget.value = item
  Object.assign(scopeForm, { actual_employer_id: null, responsibility_type: 'collaborator' })
  scopeError.value = ''
  scopeVisible.value = true
}
async function submitScope() {
  if (!scopeTarget.value || !scopeForm.actual_employer_id) {
    scopeError.value = '请选择实际工作单位'
    return
  }
  scopeSaving.value = true
  scopeError.value = ''
  try {
    if (scopeForm.responsibility_type === 'primary') {
      await employerScopesApi.replacePrimaryManager(scopeForm.actual_employer_id, scopeTarget.value.id)
    } else {
      await employerScopesApi.createEmployerScope({
        user_id: scopeTarget.value.id,
        actual_employer_id: scopeForm.actual_employer_id,
        responsibility_type: 'collaborator',
      })
    }
    ElMessage.success(scopeForm.responsibility_type === 'primary' ? '主要负责人已更换' : '实际工作单位已授权')
    scopeVisible.value = false
    await load()
  } catch (e) {
    scopeError.value = (e as Error).message
  } finally {
    scopeSaving.value = false
  }
}
async function revokeScope(scope: EmployerScope) {
  try {
    await ElMessageBox.confirm(`撤销 ${scope.user_name} 对“${scope.actual_employer_name}”的授权？`, '撤销授权', { type: 'warning' })
    await employerScopesApi.revokeEmployerScope(scope.id)
    ElMessage.success('授权已撤销')
    await load()
  } catch { /* cancelled or failed */ }
}
</script>

<template>
  <div v-loading="loading" class="operators-view">
    <div class="stat-grid">
      <StatTile label="账号总数" :value="list.length" />
      <StatTile label="正常账号" :value="activeCount" hint-type="success" />
      <StatTile label="已停用" :value="inactiveCount" hint-type="danger" />
    </div>

    <el-alert v-if="!canManage" type="info" :closable="false" title="当前账号可查看同单位操作员" description="新增、启停和重置密码需由单位主管操作。" show-icon style="margin-bottom: 0" />

    <PageCard title="单位账号列表" :count="filteredList.length" hint="单位主管与操作员统一在此管理；每个账号使用独立账号密码登录">
      <template #actions>
        <el-select v-if="auth.isAdmin()" v-model="enterpriseFilter" clearable filterable placeholder="按投保单位筛选" style="width: 200px; margin-right: 8px">
          <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
        <el-button v-if="canManage" type="primary" @click="openCreate">＋ 新增账号</el-button>
      </template>
      <el-table :data="paged" size="small">
        <el-table-column label="姓名" width="140">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <small v-if="row.id === auth.user?.id" class="muted"> 当前登录</small>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="登录账号" width="130" />
        <el-table-column prop="enterprise_name" label="所属单位" min-width="140">
          <template #default="{ row }">{{ row.enterprise_name || '—' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="120">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column label="账号类型" width="100">
          <template #default="{ row }">{{ row.enterprise_role === 'owner' || row.is_owner ? '企业主管' : '项目负责人' }}</template>
        </el-table-column>
        <el-table-column label="授权实际工作单位" min-width="220">
          <template #default="{ row }">
            <template v-if="row.enterprise_role === 'project_manager' && activeScopes(row).length">
              <el-tag v-for="scope in activeScopes(row)" :key="scope.id" size="small" :type="scope.responsibility_type === 'primary' ? 'success' : 'info'" :closable="canManage" style="margin: 2px 4px 2px 0" @close="revokeScope(scope)">
                {{ scope.actual_employer_name }} · {{ scope.responsibility_type === 'primary' ? '主要' : '协作' }}
              </el-tag>
            </template>
            <span v-else-if="row.enterprise_role === 'project_manager'" class="muted">未授权</span>
            <span v-else>全企业</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.active ? 'success' : 'info'">{{ row.active ? '正常' : '已停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.enterprise_role === 'project_manager'" link type="primary" size="small" @click="openScopeDialog(row)">授权单位</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="resetPassword(row)">重置密码</el-button>
            <el-button v-if="!row.is_owner" link :type="row.active ? 'danger' : 'success'" size="small" @click="toggleActive(row)">{{ row.active ? '停用' : '启用' }}</el-button>
            <el-button v-if="!row.has_data && row.id !== auth.user?.id" link type="danger" size="small" @click="removeOperator(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <el-dialog v-model="createVisible" title="新增单位账号" width="460px">
      <p class="dialog-hint">创建后，该账号可使用独立账号密码登录对应投保单位</p>
      <el-form :model="createForm" label-width="110px">
        <el-form-item v-if="auth.isAdmin()" label="所属投保单位" required>
          <el-select v-model="createForm.enterprise_id" filterable style="width: 100%" placeholder="请选择投保单位">
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canCreateOwner" label="账号角色" required>
          <el-radio-group v-model="createForm.role">
            <el-radio-button value="operator">项目经理(操作员)</el-radio-button>
            <el-radio-button value="owner">单位主管</el-radio-button>
          </el-radio-group>
          <div class="muted" style="font-size: 12px; line-height: 1.5; margin-top: 4px">单位主管管理全单位；项目经理仅限被授权的实际用工单位。每个单位仅一个在册主管，若已有主管，新建的主管会自动作为操作员。</div>
        </el-form-item>
        <el-form-item label="姓名" required><el-input v-model="createForm.name" placeholder="请输入姓名" /></el-form-item>
        <el-form-item label="登录账号" required><el-input v-model="createForm.username" placeholder="至少 3 位，不可与已有账号重复" /></el-form-item>
        <el-form-item label="初始密码" required><el-input v-model="createForm.password" type="password" placeholder="至少 6 位" show-password /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="createForm.phone" placeholder="选填" /></el-form-item>
        <p v-if="createError" class="error-text">{{ createError }}</p>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑账号" width="440px">
      <el-form :model="editForm" label-width="110px">
        <el-form-item label="登录账号">
          <el-input v-if="canEditUsername" v-model="editForm.username" placeholder="至少 3 位" />
          <template v-else><span>{{ editTarget?.username }}</span><small class="muted" style="margin-left: 8px">已产生业务数据的账号不可改登录账号</small></template>
        </el-form-item>
        <el-form-item v-if="canReassignEnterprise" label="所属投保单位">
          <el-select v-model="editForm.enterprise_id" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else-if="auth.isAdmin()" label="所属投保单位">
          <span>{{ editTarget?.enterprise_name || '—' }}</span>
          <small class="muted" style="margin-left: 8px">单位主管账号不能更换所属单位</small>
        </el-form-item>
        <el-form-item label="操作员姓名"><el-input v-model="editForm.name" placeholder="请输入姓名" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="editForm.phone" placeholder="选填" /></el-form-item>
        <p v-if="editError" class="error-text">{{ editError }}</p>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scopeVisible" :title="`授权实际工作单位 · ${scopeTarget?.name || ''}`" width="460px" append-to-body>
      <p class="dialog-hint">项目负责人仅能查看和操作被授权的实际工作单位。设置“主要负责人”会通过专用接口原子替换原负责人。</p>
      <el-form label-width="110px">
        <el-form-item label="实际工作单位">
          <el-select v-model="scopeForm.actual_employer_id" filterable style="width: 100%" placeholder="请选择实际工作单位">
            <el-option v-for="item in assignableEmployers" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人类型">
          <el-radio-group v-model="scopeForm.responsibility_type">
            <el-radio value="collaborator">协作负责人</el-radio>
            <el-radio value="primary">主要负责人</el-radio>
          </el-radio-group>
        </el-form-item>
        <p v-if="scopeError" class="error-text">{{ scopeError }}</p>
      </el-form>
      <template #footer>
        <el-button @click="scopeVisible = false">取消</el-button>
        <el-button type="primary" :loading="scopeSaving" @click="submitScope">保存授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.operators-view {
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
}
.dialog-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: -8px 0 16px;
}
.error-text {
  color: var(--el-color-danger);
  font-size: 12px;
  margin: -8px 0 4px;
}
</style>
