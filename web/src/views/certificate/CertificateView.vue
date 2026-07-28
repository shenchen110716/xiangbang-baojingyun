<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listEnterprises } from '@/api/enterprises'
import { listInsured } from '@/api/insured'
import { listPolicies } from '@/api/reports'
import type { InsuredPerson, Policy } from '@/api/types'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const type = computed(() => String(route.params.type))
// enterprise/selection 两种批量模式的 :id 不是单个数字：enterprise 模式是企业 id
// （跟其他模式一样是数字），selection 模式是逗号拼接的员工 id 列表，所以 idParam
// 保留原始字符串，只有需要按单个数字用的地方才转 Number。
const idParam = computed(() => String(route.params.id))
const id = computed(() => Number(route.params.id))
const loading = ref(true)
const policy = ref<Policy | null>(null)
const people = ref<InsuredPerson[]>([])
const enterpriseName = ref('')
const notFound = ref(false)
const isBatch = computed(() => type.value === 'enterprise' || type.value === 'selection')

const statusText: Record<string, string> = { active: '在保', pending: '待生效', stopped: '已停保' }

function dateOnly(value: string | null | undefined) {
  if (!value) return ''
  return new Date(value).toLocaleDateString('zh-CN')
}

async function load() {
  loading.value = true
  try {
    if (type.value === 'person') {
      const [policies, allPeople] = await Promise.all([listPolicies(), listInsured()])
      const person = allPeople.find((p) => p.id === id.value)
      if (!person) { notFound.value = true; return }
      people.value = [person]
      policy.value = policies.find((x) => x.id === person.policy_id) || null
    } else if (type.value === 'policy') {
      const policies = await listPolicies()
      const found = policies.find((x) => x.id === id.value)
      if (!found) { notFound.value = true; return }
      policy.value = found
      // 按 policy_id 让后端用 PolicyMember 权威解析这份保单下的人，不能靠
      // InsuredPerson.policy_id 在前端过滤——那个字段停保/续保时会被清空或
      // 指向别的保单，之前就是这里导致"打印证明是空的"。
      people.value = await listInsured(undefined, found.id)
    } else if (type.value === 'enterprise') {
      // 整企业批量导出：按 enterprise_id 过滤，这个字段是不会为空/不会被停保
      // 清空的稳定外键，不像 policy_id 那样有前面提到的坑，可以直接前端过滤。
      const [enterprises, allPeople] = await Promise.all([listEnterprises(), listInsured()])
      const enterprise = enterprises.find((e) => e.id === id.value)
      if (!enterprise) { notFound.value = true; return }
      enterpriseName.value = enterprise.name
      people.value = allPeople.filter((p) => p.enterprise_id === id.value)
    } else if (type.value === 'selection') {
      // 批量勾选导出：:id 是逗号拼接的员工 id 列表（来自员工列表页的多选）。
      const ids = new Set(idParam.value.split(',').map((x) => Number(x)).filter((n) => !Number.isNaN(n)))
      if (!ids.size) { notFound.value = true; return }
      const allPeople = await listInsured()
      people.value = allPeople.filter((p) => ids.has(p.id))
    } else {
      notFound.value = true
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
    notFound.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)

// 按自然月查询（用户反馈 2026-07-29）：只对"保单证明"（单份保单）开放，
// 批量证明本来就是当前快照，不涉及历史月份的问题。选中月份后只保留在
// 那个自然月里有过在保区间（哪怕只覆盖一天）的人，打印/存为 PDF 时就是
// 那个月份的名单。
const queryMonth = ref('')
const monthFilterActive = computed(() => type.value === 'policy' && !!queryMonth.value)
function pad2(n: number) {
  return String(n).padStart(2, '0')
}
function formatYMD(d: Date) {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}
// 查询月的自然月边界：月初 00:00 起、月底 24:00（即下月 1 号 00:00）止。
const queryMonthRange = computed(() => {
  if (!queryMonth.value) return null
  const [y, m] = queryMonth.value.split('-').map(Number)
  const monthStart = new Date(y, m - 1, 1)
  const monthEnd = new Date(y, m, 1)
  const monthEndDisplay = new Date(y, m, 0) // 月底最后一天，用于展示；筛选仍用 monthEnd（下月 1 号）判断"是否覆盖到月底"
  return { monthStart, monthEnd, monthEndDisplay }
})
const displayPeople = computed(() => {
  if (!monthFilterActive.value || !queryMonthRange.value) return people.value
  const { monthStart, monthEnd } = queryMonthRange.value
  return people.value.filter((p) => {
    if (!p.effective_at) return false
    const effective = new Date(p.effective_at)
    const terminated = p.terminated_at ? new Date(p.terminated_at) : null
    return effective < monthEnd && (terminated === null || terminated > monthStart)
  })
})
const queryMonthLabel = computed(() => {
  if (!queryMonth.value) return ''
  const [y, m] = queryMonth.value.split('-')
  return `${y}年${Number(m)}月`
})

const coveragePeriodText = computed(() => {
  if (type.value === 'person' && people.value[0]) {
    const p = people.value[0]
    const start = dateOnly(p.effective_at)
    const end = p.terminated_at ? dateOnly(p.terminated_at) : '长期有效'
    return start ? `${start} 零时起至 ${end === '长期有效' ? '长期' : end + ' 二十四时止'}` : '—'
  }
  if (type.value === 'policy' && monthFilterActive.value && queryMonthRange.value) {
    // 按月查询时，保单生效期间改成显示查询月的自然月区间（月初零时至月底
    // 二十四时），而不是这份保单整体的生效期间——用户反馈 2026-07-29。
    const { monthStart, monthEndDisplay } = queryMonthRange.value
    return `${formatYMD(monthStart)} 零时起至 ${formatYMD(monthEndDisplay)} 二十四时止`
  }
  if (policy.value) {
    // end_date 后端从来没赋过值（保单是持续有效的容器，没有固定到期日这个
    // 概念），之前要求两个都非空才显示，结果保单证明的生效时间永远是"—"
    // （用户反馈 2026-07-29）。改成跟"在保证明"一样：没有 end_date 就按
    // 长期有效展示。
    if (!policy.value.start_date) return '—'
    return policy.value.end_date
      ? `${policy.value.start_date} 零时起至 ${policy.value.end_date} 二十四时止`
      : `${policy.value.start_date} 零时起至长期有效`
  }
  return '—'
})

function printPage() {
  window.print()
}
</script>

<template>
  <div class="certificate-page">
    <div v-if="loading" class="state-text">正在加载…</div>
    <div v-else-if="notFound" class="state-text">未找到对应记录</div>
    <template v-else>
      <div class="toolbar no-print">
        <template v-if="type === 'policy'">
          <el-date-picker v-model="queryMonth" type="month" value-format="YYYY-MM" placeholder="按自然月查询" style="width: 160px; margin-right: 8px" />
          <span v-if="monthFilterActive" class="month-hint">{{ queryMonthLabel }}：{{ displayPeople.length }} 人</span>
        </template>
        <button class="print-btn" @click="printPage">打印 / 存为 PDF</button>
      </div>
      <div class="certificate">
        <h1 class="cert-title">{{ type === 'person' ? '在保证明' : isBatch ? '批量参保证明' : '保单证明' }}</h1>
        <ol class="cert-fields">
          <template v-if="isBatch">
            <li>被保险人名称：{{ type === 'enterprise' ? enterpriseName : (people[0]?.enterprise_name || '—') }}</li>
            <li>涉及人数：{{ people.length }} 人</li>
          </template>
          <template v-else>
            <li>险种名称：{{ policy?.plan_name || '—' }}</li>
            <li>被保险人名称：{{ policy?.enterprise_name || people[0]?.enterprise_name || '—' }}</li>
            <li>保单生效期间：{{ coveragePeriodText }}</li>
            <li>保单号：{{ policy?.policy_no || people[0]?.policy_no || '—' }}</li>
            <li v-if="monthFilterActive">查询月份：{{ queryMonthLabel }}（{{ displayPeople.length }} 人）</li>
          </template>
        </ol>
        <div v-if="isBatch" class="cert-disclaimer" style="margin-bottom:14px">批量导出可能覆盖多份保单/多个产品，具体投保产品、生效/停保时间以下表逐人明细为准。</div>
        <div class="cert-subtitle">人员明细</div>
        <table class="cert-table">
          <thead>
            <tr>
              <th>序号</th><th>姓名</th><th>证件号码</th><th>职业/工种</th>
              <th>生效起期时间</th><th>终止日期</th><th>投保产品</th><th>类别</th><th>工作单位</th><th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(p, index) in displayPeople" :key="p.id">
              <td>{{ index + 1 }}</td>
              <td>{{ p.name }}</td>
              <td>{{ p.id_number }}</td>
              <td>{{ p.position_name || p.occupation }}</td>
              <td>{{ formatDateTime(p.effective_at) }}</td>
              <td>{{ p.terminated_at ? formatDateTime(p.terminated_at) : '—' }}</td>
              <td>{{ p.plan_name || policy?.plan_name || '—' }}</td>
              <td>{{ p.occupation_class }}</td>
              <td>{{ p.actual_employer_name || '—' }}</td>
              <td>{{ statusText[p.status] || p.status }}</td>
            </tr>
            <tr v-if="!displayPeople.length">
              <td colspan="10" class="cert-empty">{{ monthFilterActive ? `${queryMonthLabel}无在保人员` : '暂无人员明细' }}</td>
            </tr>
          </tbody>
        </table>
        <p class="cert-disclaimer">
          说明：本证明由系统根据当前参保数据自动生成，仅作为参保情况的电子查询凭证，不代表保险公司或本平台的正式盖章文件；如需具备法律效力的正式保单/批单，请以保险公司出具的原件为准。
        </p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.certificate-page {
  min-height: 100vh;
  background: #f4f5f7;
  padding: 32px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.state-text {
  padding: 60px 0;
  color: #888;
}
.toolbar {
  width: 100%;
  max-width: 860px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 12px;
}
.month-hint {
  font-size: 13px;
  color: #666;
}
.print-btn {
  background: #3157e5;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 8px 18px;
  font-size: 14px;
  cursor: pointer;
}
.certificate {
  width: 100%;
  max-width: 860px;
  background: #fff;
  padding: 48px 56px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}
.cert-title {
  text-align: center;
  font-size: 24px;
  margin-bottom: 32px;
}
.cert-fields {
  list-style: none;
  padding: 0;
  margin: 0 0 24px;
  line-height: 2;
  font-size: 14px;
}
.cert-subtitle {
  font-weight: 600;
  margin-bottom: 10px;
  font-size: 14px;
}
.cert-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  margin-bottom: 24px;
}
.cert-table th,
.cert-table td {
  border: 1px solid #ccc;
  padding: 6px 8px;
  text-align: center;
}
.cert-disclaimer {
  font-size: 12px;
  color: #888;
  line-height: 1.8;
}
@media print {
  .no-print {
    display: none !important;
  }
  .certificate-page {
    background: #fff;
    padding: 0;
  }
  .certificate {
    box-shadow: none;
    padding: 0;
  }
}
</style>
