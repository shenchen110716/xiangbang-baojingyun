<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as agentsApi from '@/api/agents'
import type { Agent, AgentCommission } from '@/api/types'
import { money } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import StatTile from '@/components/StatTile.vue'
import DetailModal from '@/components/DetailModal.vue'
import AgentCommissionDialog from './AgentCommissionDialog.vue'

const loading = ref(true)
const list = ref<Agent[]>([])

async function load() {
  loading.value = true
  try {
    list.value = await agentsApi.listAgents()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const totalCommission = computed(() => list.value.reduce((s, x) => s + (x.total_commission || 0), 0))
const totalProductRelations = computed(() => list.value.reduce((s, x) => s + (x.product_count || 0), 0))

const form = reactive({ name: '', username: '', password: '123456', phone: '' })
const saving = ref(false)
async function submitCreate() {
  if (!form.name || !form.username || !form.password) { ElMessage.error('请填写姓名、账号、密码'); return }
  saving.value = true
  try {
    await agentsApi.createAgent({ ...form })
    ElMessage.success('业务员已创建')
    Object.assign(form, { name: '', username: '', password: '123456', phone: '' })
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function toggleStatus(item: Agent) {
  const next = item.active ? 'inactive' : 'active'
  try {
    await agentsApi.setAgentStatus(item.id, next)
    ElMessage.success('状态已更新')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const commissionsVisible = ref(false)
const commissionsTarget = ref<Agent | null>(null)
const commissionsList = ref<AgentCommission[]>([])
async function openCommissions(item: Agent) {
  commissionsTarget.value = item
  commissionsVisible.value = true
  commissionsList.value = await agentsApi.getAgentCommissions(item.id)
}

const dialogVisible = ref(false)
const presetAgentId = ref<number | null>(null)
function openLinkBusiness(item: Agent) {
  presetAgentId.value = item.id
  dialogVisible.value = true
}
function onCommissionSaved() {
  load()
  if (commissionsTarget.value) openCommissions(commissionsTarget.value)
}
</script>

<template>
  <div v-loading="loading" class="agents-view">
    <div class="stat-grid">
      <StatTile label="业务员总数" :value="list.length" />
      <StatTile label="对接产品关系数" :value="totalProductRelations" />
      <StatTile label="累计佣金合计" :value="money(totalCommission)" />
    </div>

    <PageCard title="录入业务员" hint="佣金比例不在业务员账号上设置：请在下方列表中点击「关联业务」逐一配置">
      <el-form :model="form" label-width="90px" class="agent-form">
        <el-form-item label="姓名" required><el-input v-model="form.name" placeholder="业务员姓名" /></el-form-item>
        <el-form-item label="登录账号" required><el-input v-model="form.username" placeholder="登录账号" /></el-form-item>
        <el-form-item label="初始密码" required><el-input v-model="form.password" /></el-form-item>
        <el-form-item label="手机号码"><el-input v-model="form.phone" placeholder="手机号" /></el-form-item>
        <el-form-item class="wide"><el-button type="primary" :loading="saving" @click="submitCreate">保存业务员</el-button></el-form-item>
      </el-form>
    </PageCard>

    <PageCard title="业务员列表" :count="list.length" hint="仅总后台可操作">
      <el-table :data="list" size="small">
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="username" label="账号" width="120" />
        <el-table-column prop="phone" label="手机" width="120" />
        <el-table-column prop="enterprise_count" label="对接企业" width="90" />
        <el-table-column prop="product_count" label="对接产品" width="90" />
        <el-table-column label="累计佣金" width="110"><template #default="{ row }">{{ money(row.total_commission) }}</template></el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag size="small" :type="row.active ? 'success' : 'info'">{{ row.active ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openCommissions(row)">佣金明细</el-button>
            <el-button link type="primary" size="small" @click="openLinkBusiness(row)">关联业务</el-button>
            <el-button link :type="row.active ? 'danger' : 'success'" size="small" @click="toggleStatus(row)">{{ row.active ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <DetailModal v-model="commissionsVisible" title="业务员佣金明细">
      <template v-if="commissionsTarget">
        <p>{{ commissionsTarget.name }} · 共 {{ commissionsList.length }} 条对接业务</p>
        <p v-for="c in commissionsList" :key="c.id">
          {{ c.enterprise_name }} · {{ c.insurer }} {{ c.plan_name }} · 累计佣金 {{ money(c.agent_commission_total) }}
        </p>
        <p v-if="!commissionsList.length">暂无对接业务</p>
      </template>
    </DetailModal>

    <AgentCommissionDialog v-model="dialogVisible" :item="null" :preset-agent-id="presetAgentId" @saved="onCommissionSaved" />
  </div>
</template>

<style scoped>
.agents-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.agent-form {
  padding: 0 20px 20px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.agent-form :deep(.wide) {
  grid-column: 1 / -1;
}
</style>
