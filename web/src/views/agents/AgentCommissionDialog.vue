<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listEnterprises } from '@/api/enterprises'
import { listPlans } from '@/api/plans'
import * as agentsApi from '@/api/agents'
import type { Agent, AgentCommission, Enterprise, InsurancePlan } from '@/api/types'
import { money } from '@/utils/format'

const props = defineProps<{ item: AgentCommission | null; presetAgentId?: number | null }>()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ saved: [] }>()

const enterprises = ref<Enterprise[]>([])
const plans = ref<InsurancePlan[]>([])
const agents = ref<Agent[]>([])
const saving = ref(false)
const errorMsg = ref('')

const form = reactive({ enterprise_id: null as number | null, plan_id: null as number | null, mode: 'rebate' as 'rebate' | 'price', rate: 0.15, sale_price: 0, status: 'active' })

watch(visible, async (isVisible) => {
  if (!isVisible) return
  errorMsg.value = ''
  const [enterpriseList, planList, agentList] = await Promise.all([listEnterprises(), listPlans(), agentsApi.listAgents()])
  enterprises.value = props.presetAgentId ? enterpriseList.filter((x) => !x.agent_id || x.agent_id === props.presetAgentId) : enterpriseList
  plans.value = planList
  agents.value = agentList
  if (props.item) {
    Object.assign(form, { enterprise_id: props.item.enterprise_id, plan_id: props.item.plan_id, mode: props.item.mode === 'markup' ? 'price' : props.item.mode, rate: props.item.rate || 0.15, sale_price: props.item.sale_price || 0, status: props.item.status })
  } else {
    Object.assign(form, { enterprise_id: null, plan_id: null, mode: 'rebate', rate: 0.15, sale_price: 0, status: 'active' })
  }
})

const resolvedAgent = computed(() => {
  if (props.item) return { id: props.item.agent_id, name: props.item.agent_name }
  const enterprise = enterprises.value.find((x) => x.id === form.enterprise_id)
  if (enterprise?.agent_id) return { id: enterprise.agent_id, name: enterprise.agent_name || '' }
  if (props.presetAgentId) return { id: props.presetAgentId, name: agents.value.find((a) => a.id === props.presetAgentId)?.name || '' }
  return null
})

const selectedPlan = computed(() => plans.value.find((p) => p.id === form.plan_id) || null)

watch(() => form.mode, () => {
  if (form.mode === 'price' && selectedPlan.value && !form.sale_price) form.sale_price = selectedPlan.value.minimum_sale_price
})

async function submit() {
  const agent = resolvedAgent.value
  if (!agent?.id) { errorMsg.value = '该单位尚未关联业务员，请先在投保单位管理中设置'; return }
  if (!props.item && (!form.enterprise_id || !form.plan_id)) { ElMessage.error('请选择投保单位和保险产品'); return }
  saving.value = true
  errorMsg.value = ''
  try {
    if (props.item) {
      await agentsApi.updateAgentCommission(props.item.id, { mode: form.mode, rate: form.rate, sale_price: form.sale_price, status: form.status })
    } else {
      await agentsApi.createAgentCommission({ agent_id: agent.id, enterprise_id: form.enterprise_id!, plan_id: form.plan_id!, rate: form.rate, mode: form.mode, sale_price: form.sale_price })
    }
    ElMessage.success('业务计价已保存')
    visible.value = false
    emit('saved')
  } catch (e) {
    errorMsg.value = (e as Error).message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="item ? '编辑业务计价' : '新增业务计价'" width="520px" append-to-body destroy-on-close>
    <el-form label-width="110px">
      <el-form-item label="投保单位">
        <el-select v-model="form.enterprise_id" :disabled="!!item" style="width: 100%">
          <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="关联业务员"><el-input :model-value="resolvedAgent?.name || '该单位尚未关联业务员'" disabled /></el-form-item>
      <el-form-item label="保险产品">
        <el-select v-model="form.plan_id" :disabled="!!item" style="width: 100%">
          <el-option v-for="p in plans" :key="p.id" :label="`${p.insurer} · ${p.name}`" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="返佣模式">
        <el-select v-model="form.mode" style="width: 100%">
          <el-option label="按比例返佣" value="rebate" />
          <el-option label="输入最终销售价格" value="price" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.mode === 'rebate'" label="业务员返佣比例"><el-input-number v-model="form.rate" :min="0" :max="1" :step="0.01" /></el-form-item>
      <el-form-item v-else label="最终销售价格"><el-input-number v-model="form.sale_price" :min="0" :step="1" /></el-form-item>
      <div v-if="selectedPlan" class="preview">
        保险原价 {{ money(selectedPlan.insurance_base_price) }} / 保司结算 {{ money(selectedPlan.policy_floor_price) }} /
        平台利润 {{ money(selectedPlan.profit_amount) }} / 销售最低价 {{ money(selectedPlan.minimum_sale_price) }} /
        总返佣 {{ money(selectedPlan.total_commission_amount) }}
      </div>
      <el-form-item label="状态">
        <el-select v-model="form.status" style="width: 100%">
          <el-option label="推广中" value="active" />
          <el-option label="已暂停" value="paused" />
        </el-select>
      </el-form-item>
      <p v-if="errorMsg" class="error-text">{{ errorMsg }}</p>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存业务计价</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.preview {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  padding: 8px 12px;
  border-radius: 6px;
  margin: 0 0 16px;
}
.error-text {
  color: var(--el-color-danger);
  font-size: 12px;
  margin: -8px 0 12px;
}
</style>
