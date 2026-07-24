<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { exportInsurerMonthlyPremium, flagInsuredPerson, getInsurerMonthlyPremiumDetail, getInsurerMonthlyPremiumSummary, getInsurerProfile, getInsurerSettlement, listInsurerClaimDocuments, listInsurerClaims, listInsurerInsured, listInsurerInvoices, listInsurerPolicies, listInsurerPositionVideos, listInsurerPositions, reviewInsurerClaim, reviewInsurerPosition, submitInsurerProfileEdit, uploadInsurerPolicyDocument } from '@/api/insurerPortal'
import type { InsurerMonthlyPremium, InsurerMonthlyPremiumRow, InsurerSettlement } from '@/api/insurerPortal'
import type { Claim, ClaimDocument, Insurer, Invoice, InsuredPerson, Policy, PositionVideo, WorkPosition } from '@/api/types'
import PageCard from '@/components/PageCard.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'

const router = useRouter()
const auth = useAuthStore()

const tab = ref('positions')
const loading = ref(true)
const passwordDialogVisible = ref(false)

const profile = ref<Insurer | null>(null)
const profileForm = reactive({ name: '', contact: '', phone: '', credit_code: '', email: '', address: '' })
const profileSaving = ref(false)

async function loadProfile() {
  profile.value = await getInsurerProfile()
  if (profile.value) {
    Object.assign(profileForm, { name: profile.value.name, contact: profile.value.contact, phone: profile.value.phone, credit_code: profile.value.credit_code, email: profile.value.email, address: profile.value.address })
  }
}

const positions = ref<WorkPosition[]>([])
async function loadPositions() {
  positions.value = await listInsurerPositions()
}

const positionVideosVisible = ref(false)
const positionVideosTarget = ref<WorkPosition | null>(null)
const positionVideos = ref<PositionVideo[]>([])
const positionVideosLoading = ref(false)
async function openPositionVideos(row: WorkPosition) {
  positionVideosTarget.value = row
  positionVideosVisible.value = true
  positionVideosLoading.value = true
  try {
    const result = await listInsurerPositionVideos(row.id)
    if (positionVideosTarget.value?.id === row.id) positionVideos.value = result
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    if (positionVideosTarget.value?.id === row.id) positionVideosLoading.value = false
  }
}

// 和 miniprogram/pages/claims/claims.js 的 maskId() 保持一致的脱敏规则。
function maskId(value: string) {
  const text = String(value || '')
  return text.length > 10 ? `${text.slice(0, 3)}${'*'.repeat(text.length - 7)}${text.slice(-4)}` : text
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

const monthlyPremium = ref<InsurerMonthlyPremium[]>([])
async function loadMonthlyPremium() {
  monthlyPremium.value = await getInsurerMonthlyPremiumSummary()
}

const monthlyDetailVisible = ref(false)
const monthlyDetailMonth = ref('')
const monthlyDetailRows = ref<InsurerMonthlyPremiumRow[]>([])
const monthlyDetailLoading = ref(false)
const monthlyDetailExporting = ref(false)
async function openMonthlyDetail(row: InsurerMonthlyPremium) {
  monthlyDetailMonth.value = row.month
  monthlyDetailVisible.value = true
  monthlyDetailLoading.value = true
  try {
    const result = await getInsurerMonthlyPremiumDetail(row.month)
    if (monthlyDetailMonth.value === row.month) monthlyDetailRows.value = result
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    if (monthlyDetailMonth.value === row.month) monthlyDetailLoading.value = false
  }
}
async function exportMonthlyDetail() {
  if (!monthlyDetailMonth.value) return
  monthlyDetailExporting.value = true
  try {
    const blob = await exportInsurerMonthlyPremium(monthlyDetailMonth.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `premium-${monthlyDetailMonth.value}.xlsx`
    link.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    monthlyDetailExporting.value = false
  }
}

const invoices = ref<Invoice[]>([])
async function loadInvoices() {
  invoices.value = await listInsurerInvoices()
}

const insuredList = ref<InsuredPerson[]>([])
async function loadInsured() {
  insuredList.value = await listInsurerInsured()
}
const insuredSearch = ref('')
const filteredInsuredList = computed(() => {
  const q = insuredSearch.value.trim()
  if (!q) return insuredList.value
  return insuredList.value.filter((x) => x.name.includes(q) || x.id_number.includes(q))
})

const flagDialogVisible = ref(false)
const flagTarget = ref<InsuredPerson | null>(null)
const flagReason = ref('')
function openFlagDialog(row: InsuredPerson) {
  flagTarget.value = row
  flagReason.value = row.insurer_flag_reason || ''
  flagDialogVisible.value = true
}
async function submitFlag() {
  if (!flagTarget.value) return
  try {
    await flagInsuredPerson(flagTarget.value.id, flagReason.value)
    ElMessage.success(flagReason.value ? '已标注' : '已取消标注')
    flagDialogVisible.value = false
    loadInsured()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const claims = ref<Claim[]>([])
async function loadClaims() {
  claims.value = await listInsurerClaims()
}

const claimDialogVisible = ref(false)
const claimTarget = ref<Claim | null>(null)
const claimDocuments = ref<ClaimDocument[]>([])
const claimDocumentsLoading = ref(false)
const claimForm = reactive({ status: 'approved' as 'approved' | 'rejected' | 'supplement', approved_amount: 0, rejection_reason: '', note: '' })
async function openClaimDialog(row: Claim) {
  claimTarget.value = row
  Object.assign(claimForm, { status: 'approved', approved_amount: 0, rejection_reason: '', note: '' })
  claimDialogVisible.value = true
  claimDocumentsLoading.value = true
  try {
    const result = await listInsurerClaimDocuments(row.id)
    if (claimTarget.value?.id === row.id) claimDocuments.value = result
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    if (claimTarget.value?.id === row.id) claimDocumentsLoading.value = false
  }
}
async function submitClaimReview() {
  if (!claimTarget.value) return
  if (claimForm.status === 'approved' && claimForm.approved_amount <= 0) { ElMessage.error('核赔通过需登记核赔金额'); return }
  if (claimForm.status === 'rejected' && !claimForm.rejection_reason.trim()) { ElMessage.error('拒赔需填写原因'); return }
  try {
    await reviewInsurerClaim(claimTarget.value.id, claimForm)
    ElMessage.success('已提交')
    claimDialogVisible.value = false
    loadClaims()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
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
    await loadMonthlyPremium()
    await loadInvoices()
    await loadInsured()
    await loadClaims()
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
        <el-tab-pane label="岗位核保" name="positions">
          <PageCard title="名下岗位" :count="positions.length" hint="仅显示已分派到本保司产品线下的岗位">
            <el-table :data="positions" size="small">
              <el-table-column prop="name" label="岗位名称" min-width="140" />
              <el-table-column prop="actual_employer_name" label="实际用工单位" min-width="160" />
              <el-table-column prop="occupation_class" label="职业类别" width="100" />
              <el-table-column label="岗位视频" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.video_count" size="small" :type="row.latest_video_status === 'approved' ? 'success' : 'info'">{{ row.video_count }} 段</el-tag>
                  <span v-else class="muted">未上传</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }"><el-tag size="small" :type="row.status === 'approved' ? 'success' : 'info'">{{ row.status === 'approved' ? '已核保' : row.status }}</el-tag></template>
              </el-table-column>
              <el-table-column label="操作" width="160">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openPositionVideos(row)">查看视频</el-button>
                  <el-button v-if="row.status !== 'approved'" link type="primary" size="small" @click="approvePosition(row)">核保通过</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!positions.length" description="暂无名下岗位" :image-size="60" />
          </PageCard>
        </el-tab-pane>

        <el-tab-pane label="参保管理" name="enrollment">
          <PageCard title="参保员工" :count="filteredInsuredList.length" hint="只能标注异常原因，不能直接修改参保状态">
            <template #actions>
              <el-input v-model="insuredSearch" placeholder="按姓名/身份证号搜索" clearable style="width: 220px" />
            </template>
            <el-table :data="filteredInsuredList" size="small">
              <el-table-column prop="name" label="姓名" width="100" />
              <el-table-column label="身份证号" min-width="180">
                <template #default="{ row }">{{ maskId(row.id_number) }}</template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
              <el-table-column label="异常标注" min-width="160">
                <template #default="{ row }">
                  <el-tag v-if="row.insurer_flag_reason" type="danger" size="small">{{ row.insurer_flag_reason }}</el-tag>
                  <span v-else class="muted">无</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }"><el-button link type="primary" size="small" @click="openFlagDialog(row)">标注</el-button></template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!filteredInsuredList.length" :description="insuredSearch ? '没有匹配的员工' : '暂无名下参保员工'" :image-size="60" />
          </PageCard>

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

        <el-tab-pane label="理赔管理" name="claims">
          <PageCard title="名下理赔案件" :count="claims.length" hint="只展示已流转到保司审核中或之后节点的案件">
            <el-table :data="claims" size="small">
              <el-table-column prop="claim_no" label="案件号" min-width="160" />
              <el-table-column prop="person_name" label="被保险人" width="100" />
              <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
              <el-table-column prop="status" label="状态" width="110" />
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openClaimDialog(row)">{{ row.status === 'insurer_review' ? '审核' : '查看' }}</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!claims.length" description="暂无名下理赔案件" :image-size="60" />
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

          <PageCard title="按月应收总保费" :count="monthlyPremium.length" hint="按人按天/按月折算，单价为结算价；点击某月查看明细并可导出">
            <el-table :data="monthlyPremium" size="small">
              <el-table-column prop="month" label="月份" width="120" />
              <el-table-column label="应收总保费" width="140"><template #default="{ row }">{{ row.total_premium }}</template></el-table-column>
              <el-table-column label="在保人数" width="100"><template #default="{ row }">{{ row.insured_count }}</template></el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }"><el-button link type="primary" size="small" @click="openMonthlyDetail(row)">查看明细</el-button></template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!monthlyPremium.length" description="暂无按月保费数据" :image-size="60" />
          </PageCard>

          <PageCard title="保单结算明细" :count="settlement?.rows.length || 0">
            <el-table :data="settlement?.rows || []" size="small">
              <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
              <el-table-column prop="plan_name" label="产品方案" min-width="140" />
              <el-table-column prop="policy_no" label="保单号" min-width="140" />
              <el-table-column label="在保人数" width="90"><template #default="{ row }">{{ row.insured_count }}</template></el-table-column>
              <el-table-column label="结算价（单价）" width="120"><template #default="{ row }">{{ row.policy_floor_price ?? '—' }}</template></el-table-column>
              <el-table-column label="保费合计" width="100"><template #default="{ row }">{{ row.premium }}</template></el-table-column>
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

        <el-tab-pane label="基本信息" name="profile">
          <PageCard title="保司基本信息" hint="修改需经平台审核通过后才会生效">
            <el-form :model="profileForm" label-width="120px" style="max-width: 480px; padding: 0 20px 20px">
              <el-form-item label="保险公司名称" required><el-input v-model="profileForm.name" /></el-form-item>
              <el-form-item label="统一社会信用代码"><el-input v-model="profileForm.credit_code" /></el-form-item>
              <el-form-item label="联系人"><el-input v-model="profileForm.contact" /></el-form-item>
              <el-form-item label="联系电话"><el-input v-model="profileForm.phone" /></el-form-item>
              <el-form-item label="邮箱"><el-input v-model="profileForm.email" /></el-form-item>
              <el-form-item label="地址"><el-input v-model="profileForm.address" /></el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="profileSaving" @click="submitProfileEdit">提交变更</el-button>
              </el-form-item>
            </el-form>
            <div v-if="profile?.pending_submitted_at" class="pending-banner">
              有一项变更正在等待平台审核：{{ profile.pending_name || profile.name }} / {{ profile.pending_contact || profile.contact }} / {{ profile.pending_phone || profile.phone }} / {{ profile.pending_credit_code || profile.credit_code }} / {{ profile.pending_email || profile.email }} / {{ profile.pending_address || profile.address }}
            </div>
          </PageCard>
        </el-tab-pane>
      </el-tabs>
    </main>

    <el-dialog v-model="flagDialogVisible" title="标注参停保异常" width="420px">
      <p v-if="flagTarget" class="dialog-subject">{{ flagTarget.name }} · {{ flagTarget.id_number }}</p>
      <el-input v-model="flagReason" type="textarea" :rows="3" placeholder="留空并提交表示取消标注" />
      <template #footer>
        <el-button @click="flagDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFlag">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="claimDialogVisible" :title="claimTarget?.status === 'insurer_review' ? '理赔审核' : '理赔详情'" width="560px">
      <template v-if="claimTarget">
        <div class="claim-detail">
          <p><b>案件号：</b>{{ claimTarget.claim_no }} · <b>状态：</b>{{ claimTarget.status }}</p>
          <p><b>被保险人：</b>{{ claimTarget.person_name }}（{{ maskId(claimTarget.id_number) }}）· <b>投保单位：</b>{{ claimTarget.enterprise_name }}</p>
          <p><b>事故时间/地点：</b>{{ claimTarget.accident_at }} · {{ claimTarget.accident_place }}（{{ claimTarget.accident_type }}）</p>
          <p><b>伤情部位：</b>{{ claimTarget.injury_part || '未填写' }} · <b>就诊医院：</b>{{ claimTarget.hospital || '未填写' }}</p>
          <p><b>诊断：</b>{{ claimTarget.diagnosis || '未填写' }}</p>
          <p><b>事故经过：</b>{{ claimTarget.description || '未填写' }}</p>
          <p><b>医疗费用：</b>{{ claimTarget.medical_cost }} · <b>申请金额：</b>{{ claimTarget.amount }}
            <template v-if="claimTarget.approved_amount"> · <b>核赔金额：</b>{{ claimTarget.approved_amount }}</template>
          </p>
          <p><b>联系人：</b>{{ claimTarget.contact_name || '未填写' }} {{ claimTarget.contact_phone }}</p>
        </div>
        <div v-loading="claimDocumentsLoading" class="claim-documents">
          <p class="claim-documents-title">理赔材料（{{ claimDocuments.length }}）</p>
          <ul v-if="claimDocuments.length">
            <li v-for="doc in claimDocuments" :key="doc.id">
              <a :href="doc.url" target="_blank">{{ doc.name }}</a>
              <small class="muted">（{{ doc.doc_type }} · {{ doc.status }}）</small>
            </li>
          </ul>
          <el-empty v-else description="暂无上传材料" :image-size="40" />
        </div>
      </template>

      <el-form v-if="claimTarget?.status === 'insurer_review'" :model="claimForm" label-width="100px" class="claim-review-form">
        <el-form-item label="审核结论">
          <el-radio-group v-model="claimForm.status">
            <el-radio-button value="approved">核赔通过</el-radio-button>
            <el-radio-button value="rejected">拒赔</el-radio-button>
            <el-radio-button value="supplement">打回补件</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="claimForm.status === 'approved'" label="核赔金额" required>
          <el-input-number v-model="claimForm.approved_amount" :min="0" :step="100" />
        </el-form-item>
        <el-form-item v-if="claimForm.status === 'rejected'" label="拒赔原因" required>
          <el-input v-model="claimForm.rejection_reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="claimForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimDialogVisible = false">{{ claimTarget?.status === 'insurer_review' ? '取消' : '关闭' }}</el-button>
        <el-button v-if="claimTarget?.status === 'insurer_review'" type="primary" @click="submitClaimReview">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="positionVideosVisible" :title="`${positionVideosTarget?.name || ''} · 岗位视频`" width="520px">
      <div v-loading="positionVideosLoading">
        <div v-for="video in positionVideos" :key="video.id" class="position-video-item">
          <video :src="video.url" controls preload="metadata" class="position-video-player" />
          <div class="position-video-meta">
            <el-tag size="small" :type="video.status === 'approved' ? 'success' : 'info'">{{ video.status }}</el-tag>
            <span v-if="video.review_note" class="muted">{{ video.review_note }}</span>
          </div>
        </div>
        <el-empty v-if="!positionVideosLoading && !positionVideos.length" description="暂无岗位视频" :image-size="50" />
      </div>
      <template #footer>
        <el-button @click="positionVideosVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="monthlyDetailVisible" :title="`${monthlyDetailMonth} 保费明细`" width="640px">
      <el-table v-loading="monthlyDetailLoading" :data="monthlyDetailRows" size="small">
        <el-table-column prop="person_name" label="姓名" width="100" />
        <el-table-column label="身份证号" min-width="180"><template #default="{ row }">{{ maskId(row.id_number) }}</template></el-table-column>
        <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
        <el-table-column prop="policy_no" label="保单号" min-width="140" />
        <el-table-column label="单价" width="90"><template #default="{ row }">{{ row.unit_price }}</template></el-table-column>
        <el-table-column label="应收金额" width="100"><template #default="{ row }">{{ row.amount }}</template></el-table-column>
      </el-table>
      <el-empty v-if="!monthlyDetailLoading && !monthlyDetailRows.length" description="该月暂无应收保费" :image-size="50" />
      <template #footer>
        <el-button @click="monthlyDetailVisible = false">关闭</el-button>
        <el-button type="primary" :loading="monthlyDetailExporting" @click="exportMonthlyDetail">导出明细</el-button>
      </template>
    </el-dialog>

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
.muted {
  color: var(--el-text-color-placeholder);
}
.dialog-subject {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.claim-detail p {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.6;
}
.claim-documents {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.claim-documents-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
}
.claim-documents ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
}
.claim-documents li {
  margin-bottom: 4px;
}
.claim-review-form {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.position-video-item {
  margin-bottom: 16px;
}
.position-video-player {
  width: 100%;
  border-radius: 8px;
  background: #000;
}
.position-video-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
</style>
