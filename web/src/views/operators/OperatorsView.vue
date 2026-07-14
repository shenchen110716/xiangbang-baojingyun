<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as operatorsApi from '@/api/operators'
import { listEnterprises } from '@/api/enterprises'
import type { Enterprise, Operator } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import PageCard from '@/components/PageCard.vue'
import StatTile from '@/components/StatTile.vue'

const auth = useAuthStore()
const loading = ref(true)
const list = ref<Operator[]>([])
const enterprises = ref<Enterprise[]>([])

const canManage = computed(() => auth.isAdmin() || !!auth.user?.is_owner)

async function load() {
  loading.value = true
  try {
    list.value = await operatorsApi.listOperators()
    if (auth.isAdmin()) enterprises.value = await listEnterprises()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const activeCount = computed(() => list.value.filter((x) => x.active).length)
const inactiveCount = computed(() => list.value.filter((x) => !x.active).length)

const createVisible = ref(false)
const createForm = reactive({ enterprise_id: null as number | null, name: '', username: '', password: '', phone: '' })
const createError = ref('')
const saving = ref(false)
function openCreate() {
  Object.assign(createForm, { enterprise_id: null, name: '', username: '', password: '', phone: '' })
  createError.value = ''
  createVisible.value = true
}
async function submitCreate() {
  createError.value = ''
  if (!createForm.name) { createError.value = '请输入操作员姓名'; return }
  if (createForm.username.length < 3) { createError.value = '登录账号至少 3 位'; return }
  if (createForm.password.length < 6) { createError.value = '初始密码至少 6 位'; return }
  if (auth.isAdmin() && !createForm.enterprise_id) { createError.value = '请先选择所属投保单位'; return }
  saving.value = true
  try {
    await operatorsApi.createOperator({ ...createForm, enterprise_id: createForm.enterprise_id || undefined })
    ElMessage.success('操作员已创建')
    createVisible.value = false
    load()
  } catch (e) {
    createError.value = (e as Error).message
  } finally {
    saving.value = false
  }
}

const editVisible = ref(false)
const editForm = reactive({ id: 0, name: '', phone: '' })
const editError = ref('')
const editSaving = ref(false)
function openEdit(item: Operator) {
  Object.assign(editForm, { id: item.id, name: item.name, phone: item.phone || '' })
  editError.value = ''
  editVisible.value = true
}
async function submitEdit() {
  editError.value = ''
  if (!editForm.name.trim()) { editError.value = '请输入操作员姓名'; return }
  editSaving.value = true
  try {
    await operatorsApi.updateOperator(editForm.id, { name: editForm.name.trim(), phone: editForm.phone.trim() })
    ElMessage.success('操作员信息已更新')
    editVisible.value = false
    load()
  } catch (e) {
    editError.value = (e as Error).message
  } finally {
    editSaving.value = false
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
</script>

<template>
  <div v-loading="loading" class="operators-view">
    <div class="stat-grid">
      <StatTile label="账号总数" :value="list.length" />
      <StatTile label="正常账号" :value="activeCount" hint-type="success" />
      <StatTile label="已停用" :value="inactiveCount" hint-type="danger" />
    </div>

    <el-alert v-if="!canManage" type="info" :closable="false" title="当前账号可查看同单位操作员" description="新增、启停和重置密码需由单位主管操作。" show-icon style="margin-bottom: 0" />

    <PageCard title="操作员列表" :count="list.length" hint="每个操作员使用自己的账号和密码登录">
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="openCreate">＋ 新增操作员</el-button>
      </template>
      <el-table :data="list" size="small">
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
          <template #default="{ row }">{{ row.is_owner ? '单位主管' : '操作员' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.active ? 'success' : 'info'">{{ row.active ? '正常' : '已停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="resetPassword(row)">重置密码</el-button>
            <el-button v-if="!row.is_owner" link :type="row.active ? 'danger' : 'success'" size="small" @click="toggleActive(row)">{{ row.active ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <el-dialog v-model="createVisible" title="新增操作员" width="440px">
      <p class="dialog-hint">创建后，操作员可使用独立账号登录同一单位用户端</p>
      <el-form :model="createForm" label-width="110px">
        <el-form-item v-if="auth.isAdmin()" label="所属投保单位">
          <el-select v-model="createForm.enterprise_id" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作员姓名"><el-input v-model="createForm.name" placeholder="请输入姓名" /></el-form-item>
        <el-form-item label="登录账号"><el-input v-model="createForm.username" placeholder="至少 3 位，不可与已有账号重复" /></el-form-item>
        <el-form-item label="初始密码"><el-input v-model="createForm.password" type="password" placeholder="至少 6 位" show-password /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="createForm.phone" placeholder="选填" /></el-form-item>
        <p v-if="createError" class="error-text">{{ createError }}</p>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建操作员</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑操作员" width="440px">
      <el-form :model="editForm" label-width="110px">
        <el-form-item label="操作员姓名"><el-input v-model="editForm.name" placeholder="请输入姓名" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="editForm.phone" placeholder="选填" /></el-form-item>
        <p v-if="editError" class="error-text">{{ editError }}</p>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
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
