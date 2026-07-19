<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  exportAgentCommissions,
  getAgentBalances,
  getAgentCommissionDetails,
  getAgentPayments,
  getAgentProducts,
  getAgentStatements,
} from '@/api/agentPortal'
import type {
  AgentBalances,
  AgentCommissionRow,
  AgentPayment,
  AgentProduct,
  AgentStatement,
} from '@/api/types'
import { money } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'
import PageCard from '@/components/PageCard.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'
import HelpDrawer from '@/components/HelpDrawer.vue'

const helpVisible = ref(false)

const router = useRouter()
const auth = useAuthStore()

const tab = ref('products')
const loading = ref(true)
const exporting = ref(false)
const passwordDialogVisible = ref(false)

const products = ref<AgentProduct[]>([])
const balances = ref<AgentBalances | null>(null)
const rows = ref<AgentCommissionRow[]>([])
const statements = ref<AgentStatement[]>([])
const payments = ref<AgentPayment[]>([])

const STATUS_LABEL: Record<string, string> = {
  draft: '草稿',
  confirmed: '已确认',
  partially_paid: '部分支付',
  paid: '已支付',
  void: '已作废',
}

async function load() {
  loading.value = true
  try {
    if (!auth.user) await auth.loadProfile()
    if (auth.user?.role !== 'salesperson') {
      router.replace({ name: 'home' })
      return
    }
    const [p, b, r, s, pay] = await Promise.all([
      getAgentProducts(),
      getAgentBalances(),
      getAgentCommissionDetails(),
      getAgentStatements(),
      getAgentPayments(),
    ])
    products.value = p
    balances.value = b
    rows.value = r
    statements.value = s
    payments.value = pay
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function runExport() {
  exporting.value = true
  try {
    const blob = await exportAgentCommissions()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'my-commissions.xlsx'
    link.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    exporting.value = false
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
  <div class="agent-portal">
    <header class="portal-header">
      <div class="portal-brand">响帮帮保经云 · 业务员工作台</div>
      <div class="portal-actions">
        <span class="portal-user">{{ auth.user?.name }}</span>
        <el-button size="small" :icon="'QuestionFilled'" @click="helpVisible = true">帮助</el-button>
        <el-button size="small" @click="passwordDialogVisible = true">修改密码</el-button>
        <el-button size="small" @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="portal-body" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane label="产品中心" name="products">
          <PageCard title="全部在售产品" subtitle="平台最低销售价由后端计算，仅供报价参考">
            <el-table :data="products" size="small" style="width: 100%">
              <el-table-column prop="insurer" label="保司" min-width="110" />
              <el-table-column prop="name" label="产品名称" min-width="150" />
              <el-table-column prop="occupation_classes" label="职业类别" width="110" />
              <el-table-column label="计费" width="90">
                <template #default="{ row }">{{ row.billing_mode === 'monthly' ? '按月' : '按天' }}</template>
              </el-table-column>
              <el-table-column label="平台最低售价" width="130">
                <template #default="{ row }">{{ money(row.min_sale_price) }}</template>
              </el-table-column>
              <el-table-column label="我的佣金" width="110">
                <template #default="{ row }">
                  <el-tag v-if="row.my_commission_status === '未配置'" type="info" size="small">未配置</el-tag>
                  <el-tag v-else type="success" size="small">已配置</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!products.length" description="暂无在售产品" :image-size="60" />
          </PageCard>
        </el-tab-pane>

        <el-tab-pane label="我的佣金" name="commissions">
          <div class="stat-grid">
            <StatTile label="预估累计佣金" :value="balances ? money(balances.estimated_total) : '—'" />
            <StatTile label="待结算" :value="balances ? money(balances.pending_settlement) : '—'" />
            <StatTile label="待支付" :value="balances ? money(balances.pending_payment) : '—'" />
            <StatTile label="已支付" :value="balances ? money(balances.paid) : '—'" />
          </div>

          <PageCard title="佣金明细" :count="rows.length">
            <template #actions>
              <el-button size="small" type="primary" :loading="exporting" @click="runExport">导出</el-button>
            </template>
            <el-table :data="rows" size="small" style="width: 100%">
              <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
              <el-table-column prop="plan_name" label="产品方案" min-width="140" />
              <el-table-column prop="insurer" label="保司" min-width="100" />
              <el-table-column prop="insured_count" label="在保人数" width="100" />
              <el-table-column label="平台最低价" width="120">
                <template #default="{ row }">{{ money(row.min_sale_price) }}</template>
              </el-table-column>
              <el-table-column label="销售价（我的价格）" width="150">
                <template #default="{ row }">{{ money(row.sale_price) }}</template>
              </el-table-column>
              <el-table-column label="累计佣金" width="120">
                <template #default="{ row }">{{ money(row.amount) }}</template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
            </el-table>
            <el-empty v-if="!rows.length" description="暂无绑定的投保单位或产品" :image-size="60" />
          </PageCard>
        </el-tab-pane>

        <el-tab-pane label="结算与付款" name="settlement">
          <PageCard title="佣金结算单" :count="statements.length">
            <el-table :data="statements" size="small" style="width: 100%">
              <el-table-column prop="statement_no" label="结算单号" min-width="160" />
              <el-table-column label="结算期间" min-width="180">
                <template #default="{ row }">{{ row.period_start }} ~ {{ row.period_end }}</template>
              </el-table-column>
              <el-table-column label="金额" width="120">
                <template #default="{ row }">{{ money(row.total_amount) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">{{ STATUS_LABEL[row.status] || row.status }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!statements.length" description="暂无结算单" :image-size="60" />
          </PageCard>

          <PageCard title="平台付款记录" :count="payments.length">
            <el-table :data="payments" size="small" style="width: 100%">
              <el-table-column prop="paid_at" label="付款时间" min-width="170" />
              <el-table-column prop="channel" label="渠道" width="100" />
              <el-table-column prop="transaction_no" label="流水号" min-width="140" />
              <el-table-column label="金额" width="120">
                <template #default="{ row }">{{ money(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="已分配" width="120">
                <template #default="{ row }">{{ money(row.allocated_amount) }}</template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!payments.length" description="暂无付款记录" :image-size="60" />
          </PageCard>
        </el-tab-pane>
      </el-tabs>
    </main>

    <PasswordChangeDialog v-model="passwordDialogVisible" />
    <HelpDrawer v-model="helpVisible" role="salesperson" />
  </div>
</template>

<style scoped>
.agent-portal {
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
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
@media (max-width: 720px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
