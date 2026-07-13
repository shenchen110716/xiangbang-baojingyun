<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getReports } from '@/api/reports'
import type { ReportRow } from '@/api/types'
import { money } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'

const loading = ref(true)
const rows = ref<ReportRow[]>([])
const currencyIds = new Set(['premium', 'settlement', 'commission'])

async function load() {
  loading.value = true
  try {
    rows.value = await getReports()
  } finally {
    loading.value = false
  }
}
onMounted(load)

function displayValue(row: ReportRow) {
  return currencyIds.has(row.id) ? money(row.value) : String(row.value)
}

function exportCsv() {
  const header = ['报表', '周期', '指标', '说明']
  const csv = '﻿' + [header, ...rows.value.map((r) => [r.name, r.period, displayValue(r), r.detail])]
    .map((r) => r.map((v) => `"${String(v).replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `响帮帮保经云-报表-${Date.now()}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}
</script>

<template>
  <div v-loading="loading" class="reports-view">
    <div class="stat-grid">
      <div v-for="row in rows" :key="row.id" class="stat-item">
        <span>{{ row.name }}</span>
        <b>{{ displayValue(row) }}</b>
      </div>
    </div>

    <PageCard title="报表中心" :count="rows.length" hint="销售保费、保司结算和返佣金额统一统计">
      <template #actions>
        <el-button type="primary" @click="exportCsv">导出报表</el-button>
      </template>
      <el-table :data="rows" size="small">
        <el-table-column prop="name" label="报表名称" min-width="140" />
        <el-table-column prop="period" label="周期" min-width="160" />
        <el-table-column label="指标" width="140"><template #default="{ row }">{{ displayValue(row) }}</template></el-table-column>
        <el-table-column prop="detail" label="口径说明" min-width="200" />
      </el-table>
    </PageCard>
  </div>
</template>

<style scoped>
.reports-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.stat-item {
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 9px;
  padding: 16px 18px;
  display: grid;
  gap: 10px;
}
.stat-item span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.stat-item b {
  font-size: 20px;
}
</style>
