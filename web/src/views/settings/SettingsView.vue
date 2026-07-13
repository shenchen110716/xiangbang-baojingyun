<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changePassword } from '@/api/auth'
import { getProviderStatus, listAuditLogs } from '@/api/misc'
import type { AuditLogItem, ProviderStatus } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(true)
const providers = ref<ProviderStatus | null>(null)
const logs = ref<AuditLogItem[]>([])

async function load() {
  loading.value = true
  try {
    const [providerStatus, logRows] = await Promise.all([getProviderStatus(), listAuditLogs(30)])
    providers.value = providerStatus
    logs.value = logRows
  } finally {
    loading.value = false
  }
}
onMounted(load)

const roleText = () => (auth.user?.role === 'admin' ? '平台管理员' : auth.user?.is_owner ? '单位主管' : '企业操作员')

async function switchUser() {
  try {
    await ElMessageBox.confirm('确认切换登录账号吗？将退出当前登录状态。', '切换登录用户', { type: 'warning' })
  } catch { return }
  auth.logout()
  router.push({ name: 'login' })
}

async function logout() {
  try {
    await ElMessageBox.confirm('确认退出当前账号？', '退出登录', { type: 'warning' })
  } catch { return }
  auth.logout()
  router.push({ name: 'login' })
}

const pwdVisible = ref(false)
const pwdForm = reactive({ current_password: '', new_password: '', confirm_password: '' })
const pwdError = ref('')
const pwdSaving = ref(false)
function openPasswordChange() {
  Object.assign(pwdForm, { current_password: '', new_password: '', confirm_password: '' })
  pwdError.value = ''
  pwdVisible.value = true
}
async function submitPasswordChange() {
  pwdError.value = ''
  if (pwdForm.new_password.length < 6) { pwdError.value = '新密码至少 6 位'; return }
  if (pwdForm.new_password !== pwdForm.confirm_password) { pwdError.value = '两次输入的新密码不一致'; return }
  pwdSaving.value = true
  try {
    await changePassword(pwdForm.current_password, pwdForm.new_password)
    ElMessage.success('密码已修改，请妥善保存')
    pwdVisible.value = false
  } catch (e) {
    pwdError.value = (e as Error).message
  } finally {
    pwdSaving.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="settings-view">
    <div class="two-col">
      <PageCard title="当前账号">
        <div class="account-block">
          <p><b>{{ auth.user?.name }}</b><br />{{ auth.user?.username }} · {{ roleText() }}</p>
          <div class="account-actions">
            <el-button @click="openPasswordChange">修改登录密码</el-button>
            <el-button @click="router.push({ name: 'operators' })">操作员管理</el-button>
            <el-button @click="switchUser">切换登录用户</el-button>
            <el-button type="danger" @click="logout">退出登录</el-button>
          </div>
        </div>
      </PageCard>
      <PageCard title="外部服务状态">
        <div v-if="providers" class="provider-block">
          <p>运行模式：<b>{{ providers.mode }}</b></p>
          <p>保司 API：{{ providers.insurer_api ? '已配置' : '未配置' }}</p>
          <p>短信服务：{{ providers.sms ? '已配置' : '未配置' }}</p>
          <p>邮件服务：{{ providers.email ? '已配置' : '未配置' }}</p>
          <p>支付服务：{{ providers.payment ? '已配置' : '未配置' }}</p>
        </div>
      </PageCard>
    </div>

    <PageCard title="最近操作日志" :count="logs.length" hint="关键新增、修改、审核与状态变更均留痕">
      <el-table :data="logs" size="small">
        <el-table-column label="时间" width="160"><template #default="{ row }">{{ formatDateTime(row.created_at) }}</template></el-table-column>
        <el-table-column prop="operator" label="操作员" width="110" />
        <el-table-column prop="action" label="动作" width="110" />
        <el-table-column label="对象" width="150"><template #default="{ row }">{{ row.object_type }} #{{ row.object_id }}</template></el-table-column>
        <el-table-column label="说明" min-width="160"><template #default="{ row }">{{ row.detail || '—' }}</template></el-table-column>
      </el-table>
    </PageCard>

    <el-dialog v-model="pwdVisible" title="修改登录密码" width="420px">
      <el-form :model="pwdForm" label-width="110px">
        <el-form-item label="当前密码"><el-input v-model="pwdForm.current_password" type="password" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="pwdForm.new_password" type="password" show-password /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="pwdForm.confirm_password" type="password" show-password /></el-form-item>
        <p v-if="pwdError" class="error-text">{{ pwdError }}</p>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="submitPasswordChange">保存新密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.settings-view {
  display: grid;
  gap: 18px;
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.account-block,
.provider-block {
  padding: 4px 20px 20px;
}
.account-block p {
  font-size: 13px;
  margin: 0 0 14px;
}
.account-actions {
  display: grid;
  gap: 8px;
}
.account-actions .el-button {
  margin-left: 0;
  width: 100%;
}
.provider-block p {
  font-size: 13px;
  margin: 0 0 8px;
}
.error-text {
  color: var(--el-color-danger);
  font-size: 12px;
  margin: -8px 0 4px;
}
@media (max-width: 900px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
