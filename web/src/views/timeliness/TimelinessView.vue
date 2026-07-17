<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  exportTimeliness,
  getTimelinessDataQuality,
  getTimelinessDetails,
  getTimelinessSummary,
  recalculateTimeliness,
  type TimelinessFilters,
} from '@/api/timeliness'
import type { TimelinessDetail, TimelinessSummary } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import PageCard from '@/components/PageCard.vue'

const auth = useAuthStore()
const isOwner = computed(() => auth.user?.role === 'admin' || auth.user?.enterprise_role === 'owner')

const summary = ref<TimelinessSummary | null>(null)
const rows = ref<TimelinessDetail[]>([])
const dataQuality = ref<TimelinessDetail[]>([])
const loading = ref(true)
const exporting = ref(false)
const recalculating = ref(false)
const filters = ref<TimelinessFilters>({})

const STATUS_LABEL: Record<string, string> = {
  timely: '及时',
  early: '提前',
  late: '延迟',
  missing: '漏办',
  premature: '提前停保',
  pending: '未到期',
  unmatched: '待匹配',
  conflict: '冲突',
}

const REASON_LABEL: Record<string, string> = {
  normal: '正常',
  source_feedback_late: '源头反馈晚',
  operator_processing_late: '操作员处理晚',
  system_processing_late: '系统处理晚',
  insurer_confirmation_late: '保司确认晚',
  unassigned_responsibility: '当时无负责人',
}

const STATUS_TYPE: Record<string, string> = {
  timely: 'success',
  early: 'success',
  late: 'warning',
  missing: 'danger',
  premature: 'danger',
  pending: 'info',
  unmatched: 'info',
  conflict: 'danger',
}

/** null 表示没有应办事件——显示「—」而不是 0%，空项目既不完美也不糟糕。 */
function rate(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : `${value}%`
}

function days(seconds: number) {
  if (!seconds) return '0'
  return (seconds / 86400).toFixed(1)
}

async function load() {
  loading.value = true
  try {
    const [s, d, q] = await Promise.all([
      getTimelinessSummary(filters.value),
      getTimelinessDetails(filters.value),
      getTimelinessDataQuality(),
    ])
    summary.value = s
    rows.value = d
    dataQuality.value = q
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function reset() {
  filters.value = {}
  load()
}

async function runExport() {
  exporting.value = true
  try {
    const blob = await exportTimeliness(filters.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'timeliness-details.xlsx'
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功，本次导出已记入审计')
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    exporting.value = false
  }
}

async function runRecalculate() {
  recalculating.value = true
  try {
    const result = await recalculateTimeliness()
    ElMessage.success(`已重算 ${result.processed} 条${result.failed ? `，${result.failed} 条失败` : ''}`)
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    recalculating.value = false
  }
}
</script>

<template>
  <PageCard title="参停保及时率" subtitle="真实用工事实与保障期比对结果">
    <template #actions>
      <el-button v-if="isOwner" :loading="recalculating" @click="runRecalculate">重算</el-button>
      <el-button type="primary" :loading="exporting" @click="runExport">导出明细</el-button>
    </template>

    <el-row v-loading="loading" :gutter="12" class="cards">
      <el-col :xs="12" :sm="8" :md="6" v-for="card in [
        { label: '参保及时率', value: rate(summary?.enrollment_rate), hint: `应参保 ${summary?.enrollment_due ?? 0}` },
        { label: '停保及时率', value: rate(summary?.termination_rate), hint: `应停保 ${summary?.termination_due ?? 0}` },
        { label: '综合及时率', value: rate(summary?.composite_rate), hint: '按业务事件计' },
        { label: '反馈及时率', value: rate(summary?.feedback_rate), hint: `已判定 ${summary?.feedback_due ?? 0}` },
        { label: '操作员可归责及时率', value: rate(summary?.operator_attributable_rate), hint: `可归责 ${summary?.operator_attributable_due ?? 0}` },
        { label: '保障缺口', value: `${days(summary?.coverage_gap_seconds ?? 0)} 天`, hint: '提前停保与延迟参保合计' },
        { label: '额外保费', value: `¥${summary?.excess_premium ?? 0}`, hint: '延迟停保导致' },
        { label: '数据质量待处理', value: String(dataQuality.length), hint: '待匹配与冲突' },
      ]" :key="card.label">
        <div class="stat">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value">{{ card.value }}</div>
          <div class="stat-hint">{{ card.hint }}</div>
        </div>
      </el-col>
    </el-row>

    <el-form :inline="true" class="filters">
      <el-form-item label="操作类型">
        <el-select v-model="filters.operation_type" clearable placeholder="全部" style="width: 120px">
          <el-option label="参保" value="enrollment" />
          <el-option label="停保" value="termination" />
        </el-select>
      </el-form-item>
      <el-form-item label="及时状态">
        <el-select v-model="filters.timeliness_status" clearable placeholder="全部" style="width: 130px">
          <el-option v-for="(label, value) in STATUS_LABEL" :key="value" :label="label" :value="value" />
        </el-select>
      </el-form-item>
      <el-form-item label="责任原因">
        <el-select v-model="filters.responsibility_reason" clearable placeholder="全部" style="width: 160px">
          <el-option v-for="(label, value) in REASON_LABEL" :key="value" :label="label" :value="value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" size="small" stripe>
      <el-table-column prop="actual_business_at" label="真实业务时间" width="170" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">{{ row.operation_type === 'enrollment' ? '参保' : '停保' }}</template>
      </el-table-column>
      <el-table-column label="及时状态" width="110">
        <template #default="{ row }">
          <el-tag :type="STATUS_TYPE[row.timeliness_status] || 'info'" size="small">
            {{ STATUS_LABEL[row.timeliness_status] || row.timeliness_status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="expected_coverage_at" label="应保障时间" width="170" />
      <el-table-column prop="actual_coverage_at" label="实际保障时间" width="170" />
      <el-table-column label="延迟" width="90">
        <template #default="{ row }">{{ days(row.delay_seconds) }} 天</template>
      </el-table-column>
      <el-table-column label="保障缺口" width="100">
        <template #default="{ row }">{{ days(row.coverage_gap_seconds) }} 天</template>
      </el-table-column>
      <el-table-column label="责任原因" min-width="140">
        <template #default="{ row }">{{ REASON_LABEL[row.responsibility_reason] || row.responsibility_reason }}</template>
      </el-table-column>
      <el-table-column prop="responsible_user_id" label="责任人" width="90" />
      <el-table-column label="规则版本" width="90">
        <template #default="{ row }">v{{ row.product_rule_version }}</template>
      </el-table-column>
      <template #empty>暂无及时率结果，请先导入真实用工事实并重算</template>
    </el-table>
  </PageCard>

  <PageCard v-if="dataQuality.length" title="数据质量队列" subtitle="待匹配与冲突记录不计入任何及时率，请先处理">
    <el-table :data="dataQuality" size="small" stripe>
      <el-table-column prop="employment_fact_id" label="用工事实" width="110" />
      <el-table-column label="问题" width="120">
        <template #default="{ row }">
          <el-tag type="warning" size="small">{{ STATUS_LABEL[row.timeliness_status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="actual_business_at" label="真实业务时间" width="180" />
      <el-table-column prop="actual_employer_id" label="实际工作单位" min-width="120" />
    </el-table>
  </PageCard>
</template>

<style scoped>
.cards {
  margin-bottom: 12px;
}
.stat {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 12px;
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
  margin: 4px 0 2px;
}
.stat-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.filters {
  margin-bottom: 8px;
}
</style>
