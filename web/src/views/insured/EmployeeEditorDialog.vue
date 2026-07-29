<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listEnterprises } from '@/api/enterprises'
import { listPlans } from '@/api/plans'
import { listActualEmployers, listPositions } from '@/api/positions'
import { createInsured, setInsuredStatus, updateInsured } from '@/api/insured'
import { recognizeIdCard } from '@/api/ocr'
import { isValidIdNumber } from '@/utils/idNumber'
import { formatDateTime } from '@/utils/format'
import type { ActualEmployer, Enterprise, InsurancePlan, InsuredPerson, WorkPosition } from '@/api/types'

const props = defineProps<{ person: InsuredPerson | null }>()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ saved: [] }>()

const enterprises = ref<Enterprise[]>([])
const employers = ref<ActualEmployer[]>([])
const positions = ref<WorkPosition[]>([])
const plans = ref<InsurancePlan[]>([])
const form = reactive({
  enterprise_id: null as number | null,
  actual_employer_id: null as number | null,
  position_id: null as number | null,
  name: '',
  id_number: '',
  phone: '',
  effective_at: null as string | null,
  terminated_at: null as string | null,
})
const dailyMode = ref<'temporary' | 'custom'>('temporary')
const saving = ref(false)
// 新增模式下，第一次成功保存后归属信息（投保单位/实际用工单位/岗位）锁定，
// 弹窗不关闭，可以连续手工添加或连续 OCR 拍照添加，直到点"完成"。
const locked = ref(false)
const addedCount = ref(0)
const ocrLoading = ref(false)
const ocrHint = ref('')
// 身份证号校验位（与后端 is_valid_id_number 同一算法），手工输入和 OCR 识别都实时提示，
// 不用等提交时才发现号码打错/拍错
const idNumberInvalid = computed(() => !!form.id_number && !isValidIdNumber(form.id_number))
// 日期选择器默认时分秒都是 0：生效/停保本来就是按"零时起/二十四时止"的自然日边界算的
// （用户反馈 2026-07-28 第 4 条），选择器打开时不该默认成当前时刻。
const MIDNIGHT = new Date(2000, 0, 1, 0, 0, 0)
function pad(n: number) {
  return String(n).padStart(2, '0')
}
function toLocalIso(d: Date) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
function tomorrowMidnight() {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  d.setHours(0, 0, 0, 0)
  return toLocalIso(d)
}
function nowIso() {
  return toLocalIso(new Date())
}
function addHoursIso(iso: string, hours: number) {
  const d = new Date(iso)
  d.setHours(d.getHours() + hours)
  return toLocalIso(d)
}
// 即时生效险"保障时长"快捷选项：默认 24 小时，也可选更长档位；停保时间 =
// 生效时间 + 这个时长，跟月单"次日零时"的默认规则是两条不同的轴
// （effective_mode 而不是 billing_mode），不能和已有的临时日结开关混用
// （用户反馈 2026-07-28 第 4 条，2026-07-29 澄清：即时生效险不能套用"次日
// 零点"默认，得是"添加时间即生效，+24 小时或自选时长为止"）。
const durationHours = ref(24)
const durationOptions = [
  { label: '24 小时', value: 24 },
  { label: '48 小时', value: 48 },
  { label: '72 小时', value: 72 },
  { label: '7 天', value: 24 * 7 },
]

watch(visible, async (isVisible) => {
  if (!isVisible) return
  const [enterpriseList, employerList, positionList, planList] = await Promise.all([listEnterprises(), listActualEmployers(), listPositions(), listPlans()])
  enterprises.value = enterpriseList
  employers.value = employerList
  positions.value = positionList
  plans.value = planList
  locked.value = false
  addedCount.value = 0
  ocrHint.value = ''
  durationHours.value = 24
  if (props.person) {
    const matchedPosition = positionList.find((p) => p.id === props.person!.position_id)
    Object.assign(form, {
      enterprise_id: props.person.enterprise_id,
      actual_employer_id: matchedPosition?.actual_employer_id ?? null,
      position_id: props.person.position_id,
      name: props.person.name,
      id_number: props.person.id_number,
      phone: props.person.phone,
      effective_at: props.person.effective_at ? props.person.effective_at.replace('Z', '').slice(0, 19) : null,
      terminated_at: props.person.terminated_at ? props.person.terminated_at.replace('Z', '').slice(0, 19) : null,
    })
  } else {
    Object.assign(form, { enterprise_id: null, actual_employer_id: null, position_id: null, name: '', id_number: '', phone: '', effective_at: tomorrowMidnight(), terminated_at: null })
    dailyMode.value = 'temporary'
  }
})

// 投保单位变化时，实际用工单位/岗位跟着重选（下级归属可能不再有效）。
watch(() => form.enterprise_id, () => {
  if (locked.value) return
  form.actual_employer_id = null
  form.position_id = null
})
// 实际用工单位变化时，岗位跟着重选。
watch(() => form.actual_employer_id, () => {
  if (locked.value) return
  form.position_id = null
})

const availableEmployers = computed(() => employers.value.filter((e) => e.status === 'active' && (!form.enterprise_id || e.enterprise_id === form.enterprise_id)))
const availablePositions = computed(() =>
  positions.value.filter((p) =>
    (p.status === 'approved' || p.id === props.person?.position_id)
    && (!form.enterprise_id || p.enterprise_id === form.enterprise_id)
    && (!form.actual_employer_id || p.actual_employer_id === form.actual_employer_id),
  ),
)
const selectedPositionHint = computed(() => {
  const pos = positions.value.find((p) => p.id === form.position_id)
  if (!pos) return '当前单位暂无审核通过的岗位'
  return `${pos.actual_employer_name || pos.actual_employer} · ${pos.name} · ${pos.occupation_class}`
})
const selectedPlan = computed(() => {
  const position = positions.value.find((item) => item.id === form.position_id)
  return plans.value.find((item) => item.id === position?.plan_id)
})
const effectiveRuleHint = computed(() => selectedPlan.value?.effective_mode === 'immediate'
  ? '即时单：生效时间不得早于操作时间后 1 小时'
  : '月单：生效时间不得早于操作日次日 00:00')
const isDailyBilling = computed(() => selectedPlan.value?.billing_mode === 'daily')
const showDailyModeToggle = computed(() => !props.person && isDailyBilling.value)
// 即时生效险（effective_mode）跟按日结算（billing_mode）是两条独立的轴：
// 已有的"临时日结"开关只管 billing_mode==='daily' 时的一种特定二选一，
// 这里新增的默认值/时长选择器只管 effective_mode==='immediate'，两者可能
// 同时命中，此时以临时日结开关的两步下单流程为准（它本来就已经在服务端
// 按"添加时刻生效、+24 小时停保"处理），这里的默认值/时长选择器让位。
const isImmediateEffect = computed(() => selectedPlan.value?.effective_mode === 'immediate')
const showDurationPicker = computed(() => !props.person && isImmediateEffect.value && !showDailyModeToggle.value)
const showTerminatedPicker = computed(() =>
  !!props.person || (!showDailyModeToggle.value && !isImmediateEffect.value) || (showDailyModeToggle.value && dailyMode.value === 'custom'),
)
// 月单生效/停保时间必须准确到 00:00:00（用户反馈 2026-07-30 第 3 条）：原来
// 用的是 type="datetime"，:default-time 只影响首次打开时面板的初始时分秒，
// 用户仍然能手动滚动选出非零点的时间。月单场景直接换成 type="date"（只能
// 选日期，没有时间滚轮），从控件层面就不给选非零点的机会；即时生效险的
// 生效/停保时间本来就该是精确到分钟的真实时刻，不受这条约束。
const dateOnlyMode = computed(() => !isImmediateEffect.value)
const dateType = computed(() => (dateOnlyMode.value ? 'date' : 'datetime'))
const dateDisplayFormat = computed(() => (dateOnlyMode.value ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm'))

// 新增参保的生效/停保默认值：月单默认次日零时（原有规则），即时生效险默认
// "添加时间即生效"，停保时间 = 生效时间 + 保障时长（用户反馈 2026-07-29）。
// 只在新增流程里生效，编辑已有人员保留原始存储时间，不重算。
function applyEffectiveDefaults() {
  if (props.person) return
  if (isImmediateEffect.value) {
    form.effective_at = nowIso()
    form.terminated_at = addHoursIso(form.effective_at, durationHours.value)
  } else {
    form.effective_at = tomorrowMidnight()
    form.terminated_at = null
  }
}
// 选定岗位后才知道对应险种是月单还是即时单，据此重算默认值。
watch(() => form.position_id, (id) => {
  if (locked.value || !id) return
  applyEffectiveDefaults()
})
// 即时生效险下，保障时长变化时停保时间跟着重算。
watch(durationHours, () => {
  if (!props.person && isImmediateEffect.value) form.terminated_at = addHoursIso(form.effective_at || nowIso(), durationHours.value)
})

async function handleOcrFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  ocrLoading.value = true
  ocrHint.value = ''
  try {
    const res = await recognizeIdCard(file)
    form.name = res.name
    form.id_number = res.id_number
    ocrHint.value = res.mock ? '模拟识别结果，请核对后再保存' : '已识别，请核对后再保存'
  } catch (e) {
    ElMessage.error((e as Error).message || '识别失败，请手动填写')
  } finally {
    ocrLoading.value = false
  }
}

function resetPersonFields() {
  form.name = ''
  form.id_number = ''
  form.phone = ''
  applyEffectiveDefaults()
  ocrHint.value = ''
}

async function submit() {
  if (!props.person && !form.enterprise_id) { ElMessage.error('请选择投保单位'); return }
  if (!form.position_id) { ElMessage.error('请选择已审核岗位'); return }
  if (!form.name || !form.id_number) { ElMessage.error('请填写姓名和身份证号'); return }
  if (idNumberInvalid.value) { ElMessage.error('身份证号校验位不正确，请核对后再提交'); return }
  saving.value = true
  try {
    if (props.person) {
      const payload: Partial<InsuredPerson> = { name: form.name, id_number: form.id_number, phone: form.phone }
      if (form.position_id !== props.person.position_id) payload.position_id = form.position_id
      if (form.effective_at !== (props.person.effective_at ? props.person.effective_at.replace('Z', '').slice(0, 19) : null)) payload.effective_at = form.effective_at
      if (form.terminated_at !== (props.person.terminated_at ? props.person.terminated_at.replace('Z', '').slice(0, 19) : null)) payload.terminated_at = form.terminated_at
      await updateInsured(props.person.id, payload)
      ElMessage.success('保存成功')
      visible.value = false
    } else if (showDailyModeToggle.value && dailyMode.value === 'temporary') {
      // 临时日结：不预先算日期，创建后依次调用"参保"（生效时间取服务端默认
      // 的"参保时间本身"）和"停保"（默认停保时间=生效时间+24小时），两步
      // 都复用后端已经校正过的默认规则，避免客户端时钟和服务端不一致。
      const created = await createInsured({
        enterprise_id: form.enterprise_id!, position_id: form.position_id, name: form.name, id_number: form.id_number, phone: form.phone,
      })
      await setInsuredStatus(created.id, 'active')
      await setInsuredStatus(created.id, 'stopped')
      addedCount.value += 1
      locked.value = true
      resetPersonFields()
      ElMessage.success(`已添加第 ${addedCount.value} 人，可继续手工填写或拍照识别添加下一人`)
    } else {
      await createInsured({
        enterprise_id: form.enterprise_id!, position_id: form.position_id, name: form.name, id_number: form.id_number, phone: form.phone,
        effective_at: form.effective_at, terminated_at: form.terminated_at,
      })
      addedCount.value += 1
      locked.value = true
      resetPersonFields()
      ElMessage.success(`已添加第 ${addedCount.value} 人，可继续手工填写或拍照识别添加下一人`)
    }
    emit('saved')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="person ? '编辑参保员工' : '新增参保员工'" width="520px" append-to-body destroy-on-close>
    <el-form :model="form" label-width="110px">
      <el-form-item label="投保单位" required>
        <el-select v-model="form.enterprise_id" :disabled="!!person || locked" style="width: 100%" placeholder="请选择">
          <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="实际用工单位">
        <el-select v-model="form.actual_employer_id" :disabled="locked" clearable style="width: 100%" placeholder="不选则不限（按岗位自带的用工单位）">
          <el-option v-for="emp in availableEmployers" :key="emp.id" :label="emp.name" :value="emp.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="已审核岗位" required>
        <el-select v-model="form.position_id" :disabled="locked" style="width: 100%" placeholder="请选择">
          <el-option v-for="p in availablePositions" :key="p.id" :label="`${p.actual_employer_name || p.actual_employer} · ${p.name}`" :value="p.id" />
        </el-select>
        <small class="hint">{{ selectedPositionHint }}</small>
      </el-form-item>

      <el-form-item v-if="!person" label="拍照识别">
        <input type="file" accept="image/*" capture="environment" @change="handleOcrFile" />
        <div v-if="ocrLoading" class="hint">正在识别…</div>
        <div v-else-if="ocrHint" class="hint" style="color: var(--el-color-success)">{{ ocrHint }}</div>
        <div v-else class="hint">上传/拍摄身份证正面照可自动带出姓名和身份证号，也可直接手工填写</div>
      </el-form-item>

      <el-form-item label="被保险人姓名" required><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="身份证号" required>
        <div style="width: 100%">
          <el-input v-model="form.id_number" :class="{ 'is-invalid-id': idNumberInvalid }" />
          <div v-if="idNumberInvalid" class="hint" style="color: var(--el-color-danger)">身份证号校验位不正确，可能拍错/打错了，请核对</div>
        </div>
      </el-form-item>
      <el-form-item v-if="showDailyModeToggle" label="日结方式">
        <el-radio-group v-model="dailyMode">
          <el-radio value="temporary">临时日结（即时生效，24 小时后自动到期）</el-radio>
          <el-radio value="custom">自定义日结（手动选择起止时间）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="生效时间">
        <el-date-picker v-model="form.effective_at" :type="dateType" :format="dateDisplayFormat" value-format="YYYY-MM-DDTHH:mm:ss" :default-time="MIDNIGHT" placeholder="请选择生效日期和时间" style="width: 100%" />
        <small class="hint">{{ effectiveRuleHint }}；留空则不修改</small>
      </el-form-item>
      <el-form-item v-if="showDurationPicker" label="保障时长">
        <el-select v-model="durationHours" style="width: 100%">
          <el-option v-for="opt in durationOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <small class="hint">即时生效险停保时间 = 生效时间 + 保障时长，自动算出：{{ formatDateTime(form.terminated_at) }}</small>
      </el-form-item>
      <el-form-item v-if="showTerminatedPicker" label="停保时间">
        <el-date-picker v-model="form.terminated_at" :type="dateType" :format="dateDisplayFormat" value-format="YYYY-MM-DDTHH:mm:ss" :default-time="MIDNIGHT" placeholder="请选择停保日期和时间" style="width: 100%" />
        <small class="hint">最早为操作日次日 00:00，且必须晚于生效时间；留空则不修改</small>
      </el-form-item>
      <!-- 手机号不是审核/参保决策的关键信息，放到表单最后，避免挡住上面更重要的
           姓名/身份证号/生效停保时间（用户反馈 2026-07-28 第 4 条）。 -->
      <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>

      <div v-if="locked" class="locked-banner">
        归属信息已锁定（本次共添加 {{ addedCount }} 人），继续填写下一人信息后点"保存并继续"，或点"完成"结束。
      </div>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">{{ locked ? '完成' : '取消' }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ locked ? '保存并继续' : '保存' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  display: block;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  margin-top: 4px;
}
.is-invalid-id :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
.locked-banner {
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  margin-top: 4px;
}
</style>
