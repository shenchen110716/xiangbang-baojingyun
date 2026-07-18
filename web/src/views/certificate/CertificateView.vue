<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listInsured } from '@/api/insured'
import { listPolicies } from '@/api/reports'
import type { InsuredPerson, Policy } from '@/api/types'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const type = computed(() => String(route.params.type))
const id = computed(() => Number(route.params.id))
const loading = ref(true)
const policy = ref<Policy | null>(null)
const people = ref<InsuredPerson[]>([])
const notFound = ref(false)

const statusText: Record<string, string> = { active: '在保', pending: '待生效', stopped: '已停保' }

function dateOnly(value: string | null | undefined) {
  if (!value) return ''
  return new Date(value).toLocaleDateString('zh-CN')
}

async function load() {
  loading.value = true
  try {
    const [policies, allPeople] = await Promise.all([listPolicies(), listInsured()])
    if (type.value === 'person') {
      const person = allPeople.find((p) => p.id === id.value)
      if (!person) { notFound.value = true; return }
      people.value = [person]
      policy.value = policies.find((x) => x.id === person.policy_id) || null
    } else {
      const found = policies.find((x) => x.id === id.value)
      if (!found) { notFound.value = true; return }
      policy.value = found
      people.value = allPeople.filter((p) => p.policy_id === found.id)
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
    notFound.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)

const coveragePeriodText = computed(() => {
  if (type.value === 'person' && people.value[0]) {
    const p = people.value[0]
    const start = dateOnly(p.effective_at)
    const end = p.terminated_at ? dateOnly(p.terminated_at) : '长期有效'
    return start ? `${start} 零时起至 ${end === '长期有效' ? '长期' : end + ' 二十四时止'}` : '—'
  }
  if (policy.value) {
    return policy.value.start_date && policy.value.end_date ? `${policy.value.start_date} 零时起至 ${policy.value.end_date} 二十四时止` : '—'
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
        <button class="print-btn" @click="printPage">打印 / 存为 PDF</button>
      </div>
      <div class="certificate">
        <h1 class="cert-title">{{ type === 'person' ? '在保证明' : '保单证明' }}</h1>
        <ol class="cert-fields">
          <li>险种名称：{{ policy?.plan_name || '—' }}</li>
          <li>被保险人名称：{{ policy?.enterprise_name || people[0]?.enterprise_name || '—' }}</li>
          <li>保单生效期间：{{ coveragePeriodText }}</li>
          <li>保单号：{{ policy?.policy_no || people[0]?.policy_no || '—' }}</li>
        </ol>
        <div class="cert-subtitle">人员明细</div>
        <table class="cert-table">
          <thead>
            <tr>
              <th>序号</th><th>姓名</th><th>证件类型</th><th>证件号码</th><th>职业/工种</th>
              <th>生效起期时间</th><th>终止日期</th><th>投保产品</th><th>类别</th><th>工作单位</th><th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(p, index) in people" :key="p.id">
              <td>{{ index + 1 }}</td>
              <td>{{ p.name }}</td>
              <td>居民身份证</td>
              <td>{{ p.id_number }}</td>
              <td>{{ p.position_name || p.occupation }}</td>
              <td>{{ formatDateTime(p.effective_at) }}</td>
              <td>{{ p.terminated_at ? formatDateTime(p.terminated_at) : '—' }}</td>
              <td>{{ p.plan_name || policy?.plan_name || '—' }}</td>
              <td>{{ p.occupation_class }}</td>
              <td>{{ p.actual_employer_name || '—' }}</td>
              <td>{{ statusText[p.status] || p.status }}</td>
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
  justify-content: flex-end;
  margin-bottom: 12px;
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
