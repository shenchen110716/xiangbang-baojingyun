<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { getMyCommissions } from '@/api/agents'
import type { AgentMeResponse } from '@/api/types'
import { money } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'
import PageCard from '@/components/PageCard.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'

const router = useRouter()
const auth = useAuthStore()

const data = ref<AgentMeResponse | null>(null)
const loading = ref(true)
const passwordDialogVisible = ref(false)

async function load() {
  loading.value = true
  try {
    if (!auth.user) await auth.loadProfile()
    if (auth.user?.role !== 'salesperson') {
      router.replace({ name: 'home' })
      return
    }
    data.value = await getMyCommissions()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function logout() {
  ElMessageBox.confirm('确定要退出登录吗？', '退出登录', { type: 'warning' }).then(() => {
    auth.logout()
    router.push({ name: 'login' })
  })
}
</script>

<template>
  <div class="agent-portal">
    <header class="portal-header">
      <div class="portal-brand">响帮帮保经云 · 业务员工作台</div>
      <div class="portal-actions">
        <span class="portal-user">{{ auth.user?.name }}</span>
        <el-button size="small" @click="passwordDialogVisible = true">修改密码</el-button>
        <el-button size="small" @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="portal-body" v-loading="loading">
      <div class="stat-grid">
        <StatTile label="绑定投保单位" :value="data?.summary.enterprise_count ?? '—'" />
        <StatTile label="绑定产品数" :value="data?.summary.product_count ?? '—'" />
        <StatTile label="在保人数" :value="data?.summary.insured_count ?? '—'" />
        <StatTile label="佣金总额" :value="data ? money(data.summary.total_commission) : '—'" />
      </div>

      <PageCard title="佣金明细" :count="data?.rows.length">
        <el-table :data="data?.rows ?? []" size="small" style="width: 100%">
          <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
          <el-table-column prop="plan_name" label="产品方案" min-width="140" />
          <el-table-column prop="insurer" label="保司" min-width="100" />
          <el-table-column prop="insured_count" label="在保人数" width="100" />
          <el-table-column label="佣金" width="120">
            <template #default="{ row }">{{ money(row.agent_commission_total) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" />
        </el-table>
        <el-empty v-if="data && !data.rows.length" description="暂无绑定的投保单位或产品" :image-size="60" />
      </PageCard>
    </main>

    <PasswordChangeDialog v-model="passwordDialogVisible" />
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
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
@media (max-width: 720px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
