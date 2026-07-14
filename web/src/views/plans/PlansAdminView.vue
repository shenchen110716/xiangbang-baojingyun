<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as plansApi from '@/api/plans'
import type { InsurancePlan, PlanTier } from '@/api/types'
import { money } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import DetailModal from '@/components/DetailModal.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const loading = ref(true)
const list = ref<InsurancePlan[]>([])
const search = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await plansApi.listPlans()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  if (!search.value) return list.value
  const q = search.value.toLowerCase()
  return list.value.filter((x) => [x.insurer, x.name, x.insurer_email].some((v) => (v || '').toLowerCase().includes(q)))
})
const { page, pageSize, total: pagedTotal, paged } = usePagedList(filtered)

// ---- create/edit ----
const editingId = ref<number | null>(null)
const form = reactive({
  insurer: '', insurer_email: '', name: '', occupation_classes: '1-4类', coverage: '',
  price: 0, commission_rate: 0.18, profit_amount: 0, payment_mode: '企业直投',
  billing_mode: 'monthly' as 'monthly' | 'daily', effective_mode: 'next_day' as 'next_day' | 'immediate',
})
function resetForm() {
  editingId.value = null
  Object.assign(form, { insurer: '', insurer_email: '', name: '', occupation_classes: '1-4类', coverage: '', price: 0, commission_rate: 0.18, profit_amount: 0, payment_mode: '企业直投', billing_mode: 'monthly', effective_mode: 'next_day' })
}
function editPlan(item: InsurancePlan) {
  editingId.value = item.id
  Object.assign(form, {
    insurer: item.insurer, insurer_email: item.insurer_email, name: item.name, occupation_classes: item.occupation_classes,
    coverage: item.coverage, price: item.price, commission_rate: item.commission_rate, profit_amount: item.profit_amount,
    payment_mode: item.payment_mode, billing_mode: item.billing_mode, effective_mode: item.effective_mode,
  })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
const saving = ref(false)
async function submitForm() {
  if (!form.insurer || !form.name || !form.coverage) { ElMessage.error('请填写保险公司、方案名称、保障范围'); return }
  const payload = { ...form }
  if (payload.effective_mode === 'immediate') payload.billing_mode = 'daily'
  saving.value = true
  try {
    if (editingId.value) await plansApi.updatePlan(editingId.value, payload)
    else await plansApi.createPlan(payload)
    ElMessage.success(editingId.value ? '方案已更新' : '保险方案已创建')
    resetForm()
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
function onEffectiveChange() {
  if (form.effective_mode === 'immediate') form.billing_mode = 'daily'
}

async function toggleStatus(item: InsurancePlan) {
  const next = item.status === 'active' ? 'paused' : 'active'
  try {
    await ElMessageBox.confirm(`确认${next === 'paused' ? '暂停' : '恢复'}该方案？`, '操作确认', { type: 'warning' })
  } catch { return }
  await plansApi.setPlanStatus(item.id, next)
  load()
}
async function removePlan(item: InsurancePlan) {
  try {
    await ElMessageBox.confirm(`确认删除「${item.name}」？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await plansApi.deletePlan(item.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const detailVisible = ref(false)
const detailItem = ref<InsurancePlan | null>(null)
function openDetail(item: InsurancePlan) {
  detailItem.value = item
  detailVisible.value = true
}

// ---- plan tiers (职业类别分档定价) ----
const tiersVisible = ref(false)
const tiersTarget = ref<InsurancePlan | null>(null)
const tiersList = ref<PlanTier[]>([])
const tierForm = reactive({ occupation_class: '1-3类' as '1-3类' | '4类' | '5类' | '超5类', price: 0, coverage: '' })
async function openTiers(item: InsurancePlan) {
  tiersTarget.value = item
  Object.assign(tierForm, { occupation_class: '1-3类', price: item.price, coverage: '' })
  tiersVisible.value = true
  tiersList.value = await plansApi.listPlanTiers(item.id)
}
async function submitTier() {
  if (!tiersTarget.value || tierForm.price < 0) { ElMessage.error('请输入有效价格'); return }
  try {
    await plansApi.createPlanTier({ plan_id: tiersTarget.value.id, occupation_class: tierForm.occupation_class, price: tierForm.price, coverage: tierForm.coverage })
    ElMessage.success('类别价格已保存，将作为该类别最新生效价格')
    tiersList.value = await plansApi.listPlanTiers(tiersTarget.value.id)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="plans-admin-view">
    <PageCard title="录入保险公司方案" hint="即时生效方案自动按天计费；价格保存后同步到推广、保单和报表">
      <el-form :model="form" label-width="150px" class="plan-form">
        <el-form-item label="保险公司名称" required><el-input v-model="form.insurer" placeholder="如：平安财险" /></el-form-item>
        <el-form-item label="保司收件邮箱"><el-input v-model="form.insurer_email" placeholder="underwriting@example.com" /></el-form-item>
        <el-form-item label="保险方案名称" required><el-input v-model="form.name" placeholder="如：团体意外险·标准版" /></el-form-item>
        <el-form-item label="适用职业类别">
          <el-select v-model="form.occupation_classes" style="width: 100%">
            <el-option label="1-4类" value="1-4类" />
            <el-option label="1-3类" value="1-3类" />
            <el-option label="1-6类" value="1-6类" />
            <el-option label="指定岗位核保" value="指定岗位核保" />
          </el-select>
        </el-form-item>
        <el-form-item label="保障范围与额度" required class="wide"><el-input v-model="form.coverage" type="textarea" :rows="2" placeholder="如：意外身故/伤残50万，意外医疗5万" /></el-form-item>
        <el-form-item label="保险原价（元/人/周期）" required><el-input-number v-model="form.price" :min="0" :step="1" /></el-form-item>
        <el-form-item label="总返佣比例（0-1）"><el-input-number v-model="form.commission_rate" :min="0" :max="1" :step="0.01" /></el-form-item>
        <el-form-item label="平台利润（元/人/周期）"><el-input-number v-model="form.profit_amount" :min="0" :step="1" /></el-form-item>
        <el-form-item label="支付模式">
          <el-select v-model="form.payment_mode" style="width: 100%">
            <el-option label="企业直投" value="企业直投" />
            <el-option label="平台大保单" value="平台大保单" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效模式">
          <el-select v-model="form.effective_mode" style="width: 100%" @change="onEffectiveChange">
            <el-option label="次日生效" value="next_day" />
            <el-option label="即时生效" value="immediate" />
          </el-select>
        </el-form-item>
        <el-form-item label="计费方式">
          <el-select v-model="form.billing_mode" :disabled="form.effective_mode === 'immediate'" style="width: 100%">
            <el-option label="按月计费" value="monthly" />
            <el-option label="按天计费" value="daily" />
          </el-select>
        </el-form-item>
        <el-form-item class="wide">
          <el-button type="primary" :loading="saving" @click="submitForm">{{ editingId ? '保存修改' : '保存方案' }}</el-button>
          <el-button v-if="editingId" @click="resetForm">取消编辑</el-button>
        </el-form-item>
      </el-form>
    </PageCard>

    <PageCard title="已录入方案" :count="filtered.length" hint="保司结算底价 = 保险原价 ×（1-总返佣比例）">
      <div class="filter-row"><FilterBar v-model:search="search" /></div>
      <el-table :data="paged" size="small">
        <el-table-column label="保险公司" min-width="140">
          <template #default="{ row }">
            <div>{{ row.insurer }}</div>
            <small class="muted">{{ row.insurer_email || '未设置保司邮箱' }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="方案名称" min-width="140" />
        <el-table-column label="保险原价" width="100"><template #default="{ row }">{{ money(row.insurance_base_price) }}</template></el-table-column>
        <el-table-column label="保司结算底价" width="110"><template #default="{ row }">{{ money(row.policy_floor_price) }}</template></el-table-column>
        <el-table-column label="平台利润" width="100"><template #default="{ row }">{{ money(row.profit_amount) }}</template></el-table-column>
        <el-table-column label="销售最低价" width="110"><template #default="{ row }">{{ money(row.minimum_sale_price) }}</template></el-table-column>
        <el-table-column label="总返佣金额" width="120">
          <template #default="{ row }">
            <div>{{ money(row.total_commission_amount) }}</div>
            <small class="muted">{{ (row.total_commission_rate * 100).toFixed(1) }}%</small>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '启用' : '已暂停' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" size="small" @click="editPlan(row)">修改</el-button>
            <el-button link type="primary" size="small" @click="openTiers(row)">类别价格</el-button>
            <el-button link type="primary" size="small" @click="toggleStatus(row)">{{ row.status === 'active' ? '暂停' : '恢复' }}</el-button>
            <el-button link type="danger" size="small" @click="removePlan(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <DetailModal v-model="detailVisible" title="保险方案计价详情">
      <template v-if="detailItem">
        <p><b>保险公司：</b>{{ detailItem.insurer }} · {{ detailItem.insurer_email || '未设置邮箱' }}</p>
        <p><b>保险原价：</b>{{ money(detailItem.insurance_base_price) }}</p>
        <p><b>总返佣：</b>{{ money(detailItem.total_commission_amount) }}（{{ (detailItem.total_commission_rate * 100).toFixed(1) }}%）</p>
        <p><b>保司结算底价：</b>{{ money(detailItem.policy_floor_price) }}</p>
        <p><b>平台利润：</b>{{ money(detailItem.profit_amount) }}</p>
        <p><b>销售最低价：</b>{{ money(detailItem.minimum_sale_price) }}</p>
        <p><b>计费规则：</b>{{ detailItem.effective_mode === 'immediate' ? '即时生效并按天计费' : detailItem.billing_mode === 'daily' ? '次日生效、按天计费' : '次日生效、按月计费' }}</p>
      </template>
    </DetailModal>

    <el-dialog v-model="tiersVisible" title="职业类别分档定价" width="560px">
      <p class="tier-hint">同一类别可录入多条记录，系统按创建时间取该类别最新一条生效价格用于该类别人员的定价计算；未单独设置的类别使用方案默认原价。</p>
      <el-table :data="tiersList" size="small" style="margin-bottom: 16px">
        <el-table-column prop="occupation_class" label="职业类别" width="100" />
        <el-table-column label="价格" width="100"><template #default="{ row }">{{ money(row.price) }}</template></el-table-column>
        <el-table-column prop="coverage" label="备注/保障范围" />
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '生效' : '停用' }}</el-tag></template></el-table-column>
      </el-table>
      <el-form :model="tierForm" label-width="90px">
        <el-form-item label="职业类别">
          <el-select v-model="tierForm.occupation_class" style="width: 100%">
            <el-option label="1-3类" value="1-3类" />
            <el-option label="4类" value="4类" />
            <el-option label="5类" value="5类" />
            <el-option label="超5类" value="超5类" />
          </el-select>
        </el-form-item>
        <el-form-item label="价格"><el-input-number v-model="tierForm.price" :min="0" :step="1" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="tierForm.coverage" placeholder="选填" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tiersVisible = false">关闭</el-button>
        <el-button type="primary" @click="submitTier">新增类别价格</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.plans-admin-view {
  display: grid;
  gap: 18px;
}
.plan-form {
  padding: 0 20px 20px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.plan-form :deep(.wide) {
  grid-column: 1 / -1;
}
.filter-row {
  padding: 0 20px 14px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.tier-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 0 0 12px;
}
</style>
