<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getDashboard } from '@/api/dashboard'
import type { DashboardData } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { money } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'
import PageCard from '@/components/PageCard.vue'
import { VChart } from '@/utils/echarts'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(true)
const data = ref<DashboardData | null>(null)

async function load() {
  loading.value = true
  try {
    data.value = await getDashboard()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const isAdmin = computed(() => auth.isAdmin())

const peopleDonutOption = computed(() => {
  const d = data.value
  if (!d) return {}
  const activeOnly = Math.max(d.active_people - d.pending_people, 0)
  const stopped = Math.max(d.people - d.active_people, 0)
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    color: ['#1d4ed8', '#d97706', '#cbd5e1'],
    series: [
      {
        type: 'pie',
        radius: ['55%', '75%'],
        avoidLabelOverlap: true,
        label: { show: false },
        data: [
          { name: '在保', value: activeOnly },
          { name: '待处理', value: d.pending_people },
          { name: '已退保/其他', value: stopped },
        ],
      },
    ],
  }
})

const alertBarOption = computed(() => {
  const alerts = data.value?.balance_alerts ?? []
  const sorted = [...alerts].sort((a, b) => a.days_left - b.days_left).slice(0, 8)
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 90, right: 30, top: 20, bottom: 20 },
    xAxis: { type: 'value', name: '剩余可用天数' },
    yAxis: {
      type: 'category',
      data: sorted.map((a) => `${a.enterprise_name} · ${a.account === 'premium' ? '保费' : '服务费'}`),
      axisLabel: { fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        data: sorted.map((a) => ({
          value: Math.min(a.days_left, 30),
          itemStyle: { color: a.level === 'critical' ? '#dc2626' : '#d97706' },
        })),
        barMaxWidth: 18,
      },
    ],
  }
})
</script>

<template>
  <div v-loading="loading" class="home-view">
    <div class="stat-grid">
      <StatTile v-if="isAdmin" label="参保单位" :value="data?.enterprises ?? '—'" :hint="data ? `待审核 ${data.pending_enterprises}` : ''" hint-type="warning" />
      <StatTile label="在保人数" :value="data?.active_people ?? '—'" :hint="data ? `待处理 ${data.pending_people}` : ''" hint-type="info" />
      <StatTile label="生效保单" :value="data?.active_policies ?? '—'" />
      <StatTile label="待处理理赔" :value="data?.claims_open ?? '—'" hint-type="danger" :hint="data && data.claims_open > 0 ? '需跟进' : ''" />
      <StatTile
        v-if="isAdmin"
        label="待处理停保"
        :value="data?.pending_terminations_count ?? '—'"
        :hint="data && data.pending_terminations_count > 0 ? '点击查看' : ''"
        hint-type="warning"
        style="cursor: pointer"
        @click="data && data.pending_terminations_count > 0 && router.push({ name: 'pendingTerminations' })"
      />
      <StatTile
        label="服务费可用余额"
        :value="data ? money(data.usage_available) : '—'"
        :hint="data ? `充值 ${money(data.usage_recharged)} · 已用 ${money(data.usage_consumed)}` : '点击去充值'"
        hint-type="info"
        style="cursor: pointer"
        @click="router.push({ name: 'recharge', query: { account_type: 'usage' } })"
      />
    </div>

    <PageCard title="保费账户余额" :hint="isAdmin ? '按收款账户汇总' : ''">
      <el-table :data="data?.premium_accounts ?? []" size="small" style="width: 100%">
        <el-table-column label="账户/保司" min-width="200">
          <template #default="{ row }">
            <div>{{ row.label || '未命名账户' }}</div>
            <small class="muted">{{ row.insurers.join('、') }}</small>
          </template>
        </el-table-column>
        <el-table-column label="可用余额" width="160">
          <template #default="{ row }">
            <div>{{ money(row.available ?? row.balance) }}</div>
            <small class="muted">充值 {{ money(row.recharged ?? row.balance) }} · 销售保费 {{ money(row.consumed ?? 0) }}</small>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="router.push({ name: 'recharge', query: { account_type: 'premium', insurer: row.insurers?.[0] } })">去充值</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="data && !data.premium_accounts.length" description="暂无保费账户" :image-size="60" />
    </PageCard>

    <div class="chart-grid">
      <PageCard title="人员状态分布">
        <div class="chart-box">
          <VChart v-if="data" :option="peopleDonutOption" autoresize style="height: 260px" />
        </div>
      </PageCard>
      <PageCard title="账户余额预警" :hint="isAdmin ? 'Top 8 · 按剩余可用天数排序' : ''">
        <div class="chart-box">
          <VChart v-if="data && data.balance_alerts.length" :option="alertBarOption" autoresize style="height: 260px" />
          <el-empty v-else description="暂无预警" :image-size="60" />
        </div>
      </PageCard>
    </div>

    <PageCard v-if="isAdmin" title="账户余额预警明细" :count="data?.balance_alerts.length">
      <el-table :data="data?.balance_alerts ?? []" size="small" style="width: 100%">
        <el-table-column prop="enterprise_name" label="参保单位" />
        <el-table-column label="账户">
          <template #default="{ row }">{{ row.account === 'premium' ? '保费账户' : '服务费账户' }}</template>
        </el-table-column>
        <el-table-column label="余额">
          <template #default="{ row }">{{ money(row.balance) }}</template>
        </el-table-column>
        <el-table-column label="日均消耗">
          <template #default="{ row }">{{ money(row.daily_burn) }}</template>
        </el-table-column>
        <el-table-column prop="days_left" label="剩余可用天数" />
        <el-table-column label="级别">
          <template #default="{ row }">
            <el-tag :type="row.level === 'critical' ? 'danger' : 'warning'" size="small">
              {{ row.level === 'critical' ? '紧急' : '预警' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="router.push({ name: 'recharge', query: { enterprise_id: row.enterprise_id, account_type: row.account } })">
              去充值
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>
  </div>
</template>

<style scoped>
.home-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.chart-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.chart-box {
  padding: 16px 20px 4px;
}
@media (max-width: 1100px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
.muted {
  color: var(--el-text-color-placeholder);
  font-size: 11.5px;
}
</style>
