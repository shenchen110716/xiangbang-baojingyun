<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { getInsurerProfile, getInsurerSettlement, listInsurerInvoices, listInsurerPolicies, listInsurerPositions, reviewInsurerPosition, submitInsurerProfileEdit, uploadInsurerPolicyDocument } from '@/api/insurerPortal'
import type { InsurerSettlement } from '@/api/insurerPortal'
import type { Insurer, Invoice, Policy, WorkPosition } from '@/api/types'
import PageCard from '@/components/PageCard.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'

const router = useRouter()
const auth = useAuthStore()

const tab = ref('profile')
const loading = ref(true)
const passwordDialogVisible = ref(false)

const profile = ref<Insurer | null>(null)
const profileForm = reactive({ name: '', contact: '', phone: '' })
const profileSaving = ref(false)

async function loadProfile() {
  profile.value = await getInsurerProfile()
  if (profile.value) {
    Object.assign(profileForm, { name: profile.value.name, contact: profile.value.contact, phone: profile.value.phone })
  }
}

const positions = ref<WorkPosition[]>([])
async function loadPositions() {
  positions.value = await listInsurerPositions()
}

const policies = ref<Policy[]>([])
async function loadPolicies() {
  policies.value = await listInsurerPolicies()
}

async function handlePolicyUpload(policyId: number, file: File) {
  try {
    await uploadInsurerPolicyDocument(policyId, file)
    ElMessage.success('保单文件已上传')
    loadPolicies()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const settlement = ref<InsurerSettlement | null>(null)
async function loadSettlement() {
  settlement.value = await getInsurerSettlement()
}

const invoices = ref<Invoice[]>([])
async function loadInvoices() {
  invoices.value = await listInsurerInvoices()
}

async function load() {
  loading.value = true
  try {
    if (!auth.user) await auth.loadProfile()
    if (auth.user?.role !== 'insurer') {
      router.replace({ name: 'home' })
      return
    }
    await loadProfile()
    await loadPositions()
    await loadPolicies()
    await loadSettlement()
    await loadInvoices()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function submitProfileEdit() {
  if (!profileForm.name.trim()) { ElMessage.error('请填写保险公司名称'); return }
  profileSaving.value = true
  try {
    profile.value = await submitInsurerProfileEdit(profileForm)
    ElMessage.success('已提交，等待平台审核后生效')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    profileSaving.value = false
  }
}

async function approvePosition(row: WorkPosition) {
  try {
    await reviewInsurerPosition(row.id, { status: 'approved', occupation_class: row.occupation_class, plan_id: row.plan_id })
    ElMessage.success('已核保')
    loadPositions()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function logout() {
  ElMessageBox.confirm('确定要退出登录吗？', '退出登录', { type: 'warning' }).then(() => {
    auth.logout()
    router.replace({ name: 'login' })
  })
}
</script>

<template>
  <div class="insurer-portal">
    <header class="portal-header">
      <div class="portal-brand">响帮帮无忧保 · 保司工作台</div>
      <div class="portal-actions">
        <span class="portal-user">{{ auth.user?.name }}</span>
        <el-button size="small" @click="passwordDialogVisible = true">修改密码</el-button>
        <el-button size="small" @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="portal-body" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane label="基本信息" name="profile">
          <PageCard title="保司基本信息" hint="修改需经平台审核通过后才会生效">
            <el-form :model="profileForm" label-width="120px" style="max-width: 480px; padding: 0 20px 20px">
              <el-form-item label="保险公司名称" required><el-input v-model="profileForm.name" /></el-form-item>
              <el-form-item label="联系人"><el-input v-model="profileForm.contact" /></el-form-item>
              <el-form-item label="联系电话"><el-input v-model="profileForm.phone" /></el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="profileSaving" @click="submitProfileEdit">提交变更</el-button>
              </el-form-item>
            </el-form>
            <div v-if="profile?.pending_submitted_at" class="pending-banner">
              有一项变更正在等待平台审核：{{ profile.pending_name || profile.name }} / {{ profile.pending_contact || profile.contact }} / {{ profile.pending_phone || profile.phone }}
            </div>
          </PageCard>
        </el-tab-pane>

        <el-tab-pane label="岗位核保" name="positions">
          <PageCard title="名下岗位" :count="positions.length" hint="仅显示已分派到本保司产品线下的岗位">
            <el-table :data="positions" size="small">
              <el-table-column prop="name" label="岗位名称" min-width="140" />
              <el-table-column prop="actual_employer_name" label="实际用工单位" min-width="160" />
              <el-table-column prop="occupation_class" label="职业类别" width="100" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }"><el-tag size="small" :type="row.status === 'approved' ? 'success' : 'info'">{{ row.status === 'approved' ? '已核保' : row.status }}</el-tag></template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button v-if="row.status !== 'approved'" link type="primary" size="small" @click="approvePosition(row)">核保通过</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!positions.length" description="暂无名下岗位" :image-size="60" />
          </PageCard>
        </el-tab-pane>

        <el-tab-pane label="上传保单" name="policies">
          <PageCard title="名下保单" :count="policies.length">
            <el-table :data="policies" size="small">
              <el-table-column prop="policy_no" label="保单号" min-width="160" />
              <el-table-column label="保费" width="100"><template #default="{ row }">{{ row.premium }}</template></el-table-column>
              <el-table-column label="保单文件" min-width="160">
                <template #default="{ row }">
                  <a v-if="row.document_download_url" :href="row.document_download_url" target="_blank">{{ row.document_name || '查看文件' }}</a>
                  <span v-else class="muted">未上传</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-upload :show-file-list="false" :auto-upload="false" accept=".pdf,.jpg,.jpeg,.png"
                             @change="(f: { raw: File }) => handlePolicyUpload(row.id, f.raw)">
                    <el-button link type="primary" size="small">{{ row.document_download_url ? '重新上传' : '上传' }}</el-button>
                  </el-upload>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!policies.length" description="暂无名下保单" :image-size="60" />
          </PageCard>
        </el-tab-pane>

        <el-tab-pane label="财务管理" name="settlement">
          <PageCard title="保费结算总览" hint="仅显示保费与结算价，平台内部利润/返佣数据不对保司开放">
            <div class="stat-grid">
              <div class="stat-tile">
                <div class="stat-label">在保保费合计</div>
                <div class="stat-value">{{ settlement?.total_active_premium ?? '—' }}</div>
              </div>
            </div>
          </PageCard>
          <PageCard title="保单结算明细" :count="settlement?.rows.length || 0">
            <el-table :data="settlement?.rows || []" size="small">
              <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
              <el-table-column prop="plan_name" label="产品方案" min-width="140" />
              <el-table-column prop="policy_no" label="保单号" min-width="140" />
              <el-table-column label="保费" width="100"><template #default="{ row }">{{ row.premium }}</template></el-table-column>
              <el-table-column label="结算价" width="100"><template #default="{ row }">{{ row.policy_floor_price ?? '—' }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
            </el-table>
            <el-empty v-if="!settlement?.rows.length" description="暂无结算数据" :image-size="60" />
          </PageCard>
        </el-tab-pane>

        <el-tab-pane label="发票管理" name="invoices">
          <PageCard title="名下投保单位发票申请" :count="invoices.length">
            <el-table :data="invoices" size="small">
              <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
              <el-table-column prop="account" label="费用类型" width="100">
                <template #default="{ row }">{{ row.account === 'premium' ? '保费' : '使用费' }}</template>
              </el-table-column>
              <el-table-column label="金额" width="100"><template #default="{ row }">{{ row.amount }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
            </el-table>
            <el-empty v-if="!invoices.length" description="暂无发票申请" :image-size="60" />
          </PageCard>
        </el-tab-pane>
      </el-tabs>
    </main>

    <PasswordChangeDialog v-model="passwordDialogVisible" />
  </div>
</template>

<style scoped>
.insurer-portal {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}
.portal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 28px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.portal-brand {
  font-weight: 700;
  font-size: 15px;
}
.portal-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.portal-user {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.portal-body {
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px 28px 40px;
}
.pending-banner {
  margin: 0 20px 20px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  padding: 0 20px 20px;
}
.stat-tile {
  padding: 16px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat-value {
  font-size: 20px;
  font-weight: 700;
  margin-top: 6px;
}
</style>
