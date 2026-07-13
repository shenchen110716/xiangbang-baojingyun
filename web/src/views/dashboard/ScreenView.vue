<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getDashboard, getScreenProducts } from '@/api/dashboard'
import type { DashboardData, ScreenProduct } from '@/api/types'
import { money } from '@/utils/format'
import { VChart } from '@/utils/echarts'

const loading = ref(true)
const dashboard = ref<DashboardData | null>(null)
const products = ref<ScreenProduct[]>([])

async function load() {
  loading.value = true
  try {
    const [d, p] = await Promise.all([getDashboard(), getScreenProducts()])
    dashboard.value = d
    products.value = p
  } finally {
    loading.value = false
  }
}
onMounted(load)

const sortedByPremium = computed(() => [...products.value].sort((a, b) => b.premium_total - a.premium_total).slice(0, 8))

const premiumBarOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 12, right: 20, top: 20, bottom: 60, containLabel: true },
  xAxis: {
    type: 'category',
    data: sortedByPremium.value.map((p) => p.product),
    axisLabel: { color: '#a9b6e0', rotate: 30, fontSize: 11 },
    axisLine: { lineStyle: { color: '#2c3a63' } },
  },
  yAxis: { type: 'value', axisLabel: { color: '#a9b6e0' }, splitLine: { lineStyle: { color: '#1d2947' } } },
  series: [
    {
      type: 'bar',
      data: sortedByPremium.value.map((p) => p.premium_total),
      itemStyle: { color: '#5b8bf3', borderRadius: [4, 4, 0, 0] },
      barMaxWidth: 32,
    },
  ],
}))

const insurerDonutOption = computed(() => {
  const byInsurer = new Map<string, number>()
  for (const p of products.value) {
    byInsurer.set(p.insurer, (byInsurer.get(p.insurer) || 0) + p.insured_count)
  }
  return {
    tooltip: { trigger: 'item' },
    legend: { orient: 'vertical', right: 10, top: 'center', textStyle: { color: '#a9b6e0', fontSize: 11 } },
    color: ['#5b8bf3', '#27b688', '#f39b50', '#ef6e76', '#8b7bf0', '#3fc7d6'],
    series: [
      {
        type: 'pie',
        radius: ['50%', '72%'],
        center: ['38%', '50%'],
        label: { show: false },
        data: Array.from(byInsurer.entries()).map(([name, value]) => ({ name, value })),
      },
    ],
  }
})

const totalInsuredCount = computed(() => products.value.reduce((sum, p) => sum + p.insured_count, 0))
const totalPremium = computed(() => products.value.reduce((sum, p) => sum + p.premium_total, 0))
</script>

<template>
  <div v-loading="loading" class="screen-view">
    <header class="screen-header">
      <h1>响帮帮保经云 · 经营大屏</h1>
      <span class="ts">{{ new Date().toLocaleString('zh-CN') }}</span>
    </header>

    <div class="kpi-row">
      <div class="kpi"><b>{{ dashboard?.enterprises ?? '—' }}</b><span>参保单位</span></div>
      <div class="kpi"><b>{{ dashboard?.active_people ?? '—' }}</b><span>在保人数</span></div>
      <div class="kpi"><b>{{ dashboard?.active_policies ?? '—' }}</b><span>生效保单</span></div>
      <div class="kpi"><b>{{ totalInsuredCount }}</b><span>产品覆盖人次</span></div>
      <div class="kpi"><b>{{ money(totalPremium) }}</b><span>保费规模</span></div>
      <div class="kpi"><b>{{ dashboard?.claims_open ?? '—' }}</b><span>处理中理赔</span></div>
    </div>

    <div class="panel-row">
      <section class="panel">
        <h2>保费规模 Top 8 产品</h2>
        <VChart v-if="products.length" :option="premiumBarOption" autoresize style="height: 320px" />
        <p v-else class="empty">暂无数据</p>
      </section>
      <section class="panel">
        <h2>在保人次 · 按保司分布</h2>
        <VChart v-if="products.length" :option="insurerDonutOption" autoresize style="height: 320px" />
        <p v-else class="empty">暂无数据</p>
      </section>
    </div>

    <section class="panel">
      <h2>产品明细</h2>
      <table class="product-table">
        <thead>
          <tr>
            <th>保司</th>
            <th>产品</th>
            <th>在保单位</th>
            <th>在保人数</th>
            <th>保单数</th>
            <th>保费规模</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in products" :key="p.plan_id">
            <td>{{ p.insurer }}</td>
            <td>{{ p.product }}</td>
            <td>{{ p.enterprise_count }}</td>
            <td>{{ p.insured_count }}</td>
            <td>{{ p.policy_count }}</td>
            <td>{{ money(p.premium_total) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.screen-view {
  background: radial-gradient(ellipse at top, #131c38 0%, #0a1024 65%);
  color: #e7ecff;
  margin: -28px;
  padding: 24px 28px 40px;
  min-height: calc(100vh - 64px);
}
.screen-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 20px;
}
.screen-header h1 {
  font-size: 20px;
  margin: 0;
  letter-spacing: 0.02em;
}
.ts {
  color: #6f7db3;
  font-size: 12px;
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
.kpi {
  background: rgba(91, 139, 243, 0.08);
  border: 1px solid #263259;
  border-radius: 10px;
  padding: 16px;
  display: grid;
  gap: 6px;
}
.kpi b {
  font-size: 24px;
  font-weight: 700;
}
.kpi span {
  font-size: 11px;
  color: #8792bd;
}
.panel-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}
.panel {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid #263259;
  border-radius: 10px;
  padding: 16px 18px;
}
.panel h2 {
  font-size: 13px;
  font-weight: 600;
  margin: 0 0 10px;
  color: #c3cbf0;
}
.empty {
  color: #6f7db3;
  font-size: 12px;
  text-align: center;
  padding: 60px 0;
}
.product-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.product-table th,
.product-table td {
  padding: 9px 10px;
  text-align: left;
  border-bottom: 1px solid #1d2947;
}
.product-table th {
  color: #8792bd;
  font-weight: 500;
}
@media (max-width: 1200px) {
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
  }
  .panel-row {
    grid-template-columns: 1fr;
  }
}
</style>
