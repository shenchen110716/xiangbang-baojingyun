<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as claimsApi from '@/api/claims'
import { CLAIM_RISK_TEXT, CLAIM_STATUS_TEXT, CLAIM_TRANSITIONS } from '@/api/claims'
import type { ChecklistItem, Claim, ClaimDocument, ClaimTimelineItem } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { money, formatDateTime } from '@/utils/format'

const props = defineProps<{ claimId: number | null }>()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ changed: [] }>()
const auth = useAuthStore()

const loading = ref(true)
const claim = ref<Claim | null>(null)
const checklist = ref<ChecklistItem[]>([])
const documents = ref<ClaimDocument[]>([])
const timeline = ref<ClaimTimelineItem[]>([])

async function load() {
  if (!props.claimId) return
  loading.value = true
  try {
    const [c, list, docs, tl] = await Promise.all([
      claimsApi.getClaim(props.claimId),
      claimsApi.getClaimChecklist(props.claimId),
      claimsApi.listClaimDocuments(props.claimId),
      claimsApi.getClaimTimeline(props.claimId),
    ])
    claim.value = c
    checklist.value = list
    documents.value = docs
    timeline.value = tl
  } finally {
    loading.value = false
  }
}
watch(() => [props.claimId, visible.value] as const, ([id, isVisible]) => {
  if (id && isVisible) load()
})

const availableTargets = computed(() => {
  if (!claim.value) return []
  const all = CLAIM_TRANSITIONS[claim.value.status] || []
  return auth.isEnterprise() ? all.filter((s) => s === 'submitted') : all
})

// ---- status change ----
const statusDialogVisible = ref(false)
const statusForm = reactive({ status: '', note: '', approved_amount: 0, insurer_report_no: '', rejection_reason: '', paid_at: '' })
function openStatusChange() {
  Object.assign(statusForm, { status: availableTargets.value[0] || '', note: '', approved_amount: 0, insurer_report_no: claim.value?.insurer_report_no || '', rejection_reason: '', paid_at: '' })
  statusDialogVisible.value = true
}
async function submitStatusChange() {
  if (!claim.value) return
  if (statusForm.status === 'approved' && statusForm.approved_amount <= 0) { ElMessage.error('核赔通过时必须登记核赔金额'); return }
  if (statusForm.status === 'rejected' && !statusForm.rejection_reason) { ElMessage.error('拒赔时必须填写拒赔原因'); return }
  if (statusForm.status === 'insurer_review' && !statusForm.insurer_report_no && !claim.value.insurer_report_no) { ElMessage.error('请先登记保司报案号'); return }
  try {
    await claimsApi.setClaimStatus(claim.value.id, {
      status: statusForm.status,
      note: statusForm.note,
      approved_amount: statusForm.status === 'approved' ? statusForm.approved_amount : undefined,
      insurer_report_no: statusForm.insurer_report_no || undefined,
      rejection_reason: statusForm.status === 'rejected' ? statusForm.rejection_reason : undefined,
      paid_at: statusForm.status === 'paid' ? statusForm.paid_at || undefined : undefined,
    })
    ElMessage.success('状态已更新')
    statusDialogVisible.value = false
    load()
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- document upload ----
const uploadDocType = ref('id_card')
const uploading = ref(false)
function onUploadFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !claim.value) return
  uploading.value = true
  claimsApi.uploadClaimDocument(claim.value.id, uploadDocType.value, file)
    .then(() => { ElMessage.success('材料已上传'); load(); emit('changed') })
    .catch((err) => ElMessage.error(err.message))
    .finally(() => { uploading.value = false; input.value = '' })
}
async function reviewDocument(doc: ClaimDocument, status: 'accepted' | 'rejected') {
  let review_note = ''
  if (status === 'rejected') {
    try {
      const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回材料', { inputPattern: /.+/, inputErrorMessage: '驳回原因不能为空' })
      review_note = value
    } catch { return }
  }
  try {
    await claimsApi.reviewClaimDocument(claim.value!.id, doc.id, { status, review_note })
    ElMessage.success('材料已审核')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function removeDocument(doc: ClaimDocument) {
  try {
    await ElMessageBox.confirm(`确定删除材料「${doc.name}」吗？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await claimsApi.deleteClaimDocument(claim.value!.id, doc.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const riskType: Record<string, string> = { normal: 'info', attention: 'warning', high: 'danger' }
</script>

<template>
  <el-dialog v-model="visible" title="理赔案件工作台" width="880px" append-to-body destroy-on-close @closed="emit('changed')">
    <div v-loading="loading">
      <template v-if="claim">
        <div class="header-row">
          <div>
            <b class="claim-no">{{ claim.claim_no }}</b>
            <el-tag size="small" style="margin-left: 8px">{{ CLAIM_STATUS_TEXT[claim.status] }}</el-tag>
            <el-tag size="small" :type="riskType[claim.calculated_risk] as any" style="margin-left: 6px">{{ CLAIM_RISK_TEXT[claim.calculated_risk] || claim.calculated_risk }}</el-tag>
          </div>
          <el-button v-if="availableTargets.length" type="primary" size="small" @click="openStatusChange">变更状态</el-button>
        </div>

        <el-tabs>
          <el-tab-pane label="基本信息">
            <div class="info-grid">
              <div class="row"><span>投保单位</span><b>{{ claim.enterprise_name }}</b></div>
              <div class="row"><span>被保险人</span><b>{{ claim.person_name }} · {{ claim.id_number }}</b></div>
              <div class="row"><span>岗位/单位</span><b>{{ claim.position_name }} · {{ claim.actual_employer_name }}</b></div>
              <div class="row"><span>保单/产品</span><b>{{ claim.policy_no || '—' }} · {{ claim.insurer }} {{ claim.plan_name }}</b></div>
              <div class="row"><span>事故时间</span><b>{{ claim.accident_at }}</b></div>
              <div class="row"><span>事故地点</span><b>{{ claim.accident_place }}</b></div>
              <div class="row"><span>事故类型</span><b>{{ claim.accident_type }}</b></div>
              <div class="row"><span>就诊医院</span><b>{{ claim.hospital || '—' }}</b></div>
              <div class="row"><span>诊断结果</span><b>{{ claim.diagnosis || '—' }}</b></div>
              <div class="row"><span>医疗费用</span><b>{{ money(claim.medical_cost) }}</b></div>
              <div class="row"><span>预估/核赔金额</span><b>{{ money(claim.amount) }} / {{ claim.approved_amount ? money(claim.approved_amount) : '未核定' }}</b></div>
              <div class="row"><span>联系人</span><b>{{ claim.contact_name }} {{ claim.contact_phone }}</b></div>
              <div class="row"><span>保司报案号</span><b>{{ claim.insurer_report_no || '未登记' }}</b></div>
              <div class="row"><span>当前处理人</span><b>{{ claim.current_handler || '—' }}</b></div>
              <div class="row"><span>理赔时限</span><b>{{ claim.deadline || '—' }}{{ claim.deadline_overdue ? '（已逾期）' : '' }}</b></div>
              <div class="row"><span>SLA 时限</span><b>{{ claim.sla_deadline || '—' }}{{ claim.sla_overdue ? '（已超时）' : '' }}</b></div>
              <div class="row wide"><span>案情描述</span><b>{{ claim.description }}</b></div>
              <div v-if="claim.rejection_reason" class="row wide"><span>拒赔原因</span><b>{{ claim.rejection_reason }}</b></div>
              <div v-if="claim.review_note" class="row wide"><span>审核意见</span><b>{{ claim.review_note }}</b></div>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="`材料清单 (${claim.complete_percent}%)`">
            <el-table :data="checklist" size="small" style="margin-bottom: 16px">
              <el-table-column prop="name" label="材料名称" min-width="140" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'accepted' ? 'success' : row.status === 'rejected' ? 'danger' : row.status === 'uploaded' ? 'warning' : 'info'">
                    {{ row.status === 'accepted' ? '已通过' : row.status === 'rejected' ? '已驳回' : row.status === 'uploaded' ? '待审核' : '未上传' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="review_note" label="审核意见" min-width="140" />
            </el-table>

            <div class="upload-row">
              <el-select v-model="uploadDocType" style="width: 220px">
                <el-option v-for="item in checklist" :key="item.doc_type" :label="item.name" :value="item.doc_type" />
                <el-option label="其他材料" value="other" />
              </el-select>
              <input type="file" accept=".jpg,.jpeg,.png,.heic,.pdf,.doc,.docx,.xls,.xlsx" :disabled="uploading" @change="onUploadFile" />
            </div>

            <el-table :data="documents" size="small" style="margin-top: 16px">
              <el-table-column prop="name" label="文件名" min-width="160" />
              <el-table-column prop="doc_type" label="类型" width="120" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'accepted' ? 'success' : row.status === 'rejected' ? 'danger' : 'warning'">
                    {{ row.status === 'accepted' ? '已通过' : row.status === 'rejected' ? '已驳回' : '待审核' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="220">
                <template #default="{ row }">
                  <el-link :href="row.url" target="_blank" type="primary" style="margin-right: 10px">查看</el-link>
                  <template v-if="auth.isAdmin() && row.status === 'uploaded'">
                    <el-button link type="success" size="small" @click="reviewDocument(row, 'accepted')">通过</el-button>
                    <el-button link type="danger" size="small" @click="reviewDocument(row, 'rejected')">驳回</el-button>
                  </template>
                  <el-button link type="danger" size="small" @click="removeDocument(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="时间线">
            <el-timeline>
              <el-timeline-item v-for="item in timeline" :key="item.id" :timestamp="formatDateTime(item.created_at)">
                <b>{{ item.action }}</b>
                <div v-if="item.note" class="muted">{{ item.note }}</div>
                <small class="muted">{{ item.operator }} · {{ CLAIM_STATUS_TEXT[item.node] || item.node }}</small>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>

    <el-dialog v-model="statusDialogVisible" title="变更理赔状态" width="460px" append-to-body>
      <el-form :model="statusForm" label-width="100px">
        <el-form-item label="目标状态">
          <el-select v-model="statusForm.status" style="width: 100%">
            <el-option v-for="s in availableTargets" :key="s" :label="CLAIM_STATUS_TEXT[s]" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="statusForm.status === 'insurer_review'" label="保司报案号"><el-input v-model="statusForm.insurer_report_no" /></el-form-item>
        <el-form-item v-if="statusForm.status === 'approved'" label="核赔金额"><el-input-number v-model="statusForm.approved_amount" :min="0" :step="100" /></el-form-item>
        <el-form-item v-if="statusForm.status === 'rejected'" label="拒赔原因"><el-input v-model="statusForm.rejection_reason" type="textarea" :rows="2" /></el-form-item>
        <el-form-item v-if="statusForm.status === 'paid'" label="赔付时间"><el-date-picker v-model="statusForm.paid_at" type="datetime" value-format="YYYY-MM-DD HH:mm" style="width: 100%" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="statusForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitStatusChange">确认变更</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.claim-no {
  font-size: 15px;
}
.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 24px;
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.row.wide {
  grid-column: 1 / -1;
}
.row span {
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.upload-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
