<script setup lang="ts">
import { ref, watch } from 'vue'
import type { InsuredPerson, PolicyMemberHistory } from '@/api/types'
import { getPolicyMembers } from '@/api/insured'
import { money, formatDateTime } from '@/utils/format'

const props = defineProps<{ person: InsuredPerson | null }>()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ edit: []; 'toggle-status': [] }>()

const history = ref<PolicyMemberHistory[]>([])
const historyLoading = ref(false)

watch(
  () => [props.person, visible.value] as const,
  async ([person, isVisible]) => {
    if (person && isVisible) {
      historyLoading.value = true
      try {
        history.value = await getPolicyMembers(person.id)
      } finally {
        historyLoading.value = false
      }
    }
  },
)

const statusText: Record<string, string> = { active: '在保', pending: '待审核', stopped: '已停保' }
</script>

<template>
  <el-dialog v-model="visible" title="员工详情" width="640px" append-to-body destroy-on-close>
    <div v-if="person" class="detail-grid">
      <div class="row"><span>身份证号</span><b>{{ person.id_number || '—' }}</b></div>
      <div class="row"><span>投保单位</span><b>{{ person.enterprise_name || '—' }}</b></div>
      <div class="row"><span>实际工作单位</span><b>{{ person.actual_employer_name || '—' }}</b></div>
      <div class="row"><span>岗位与类别</span><b>{{ person.position_name || person.occupation }} · {{ person.occupation_class }}</b></div>
      <div class="row"><span>保险产品</span><b>{{ person.insurer ? `${person.insurer} · ${person.plan_name}` : '未绑定产品' }}</b></div>
      <template v-if="person.insurance_base_price !== undefined">
        <div class="row"><span>保险原价</span><b>{{ money(person.insurance_base_price) }}</b></div>
        <div class="row"><span>保司结算底价</span><b>{{ money(person.policy_floor_price) }}</b></div>
        <div class="row"><span>平台利润</span><b>{{ money(person.profit_amount) }}</b></div>
        <div class="row"><span>销售最低价</span><b>{{ money(person.minimum_sale_price) }}</b></div>
        <div class="row"><span>实际销售价</span><b>{{ money(person.sale_price) }}</b></div>
        <div class="row"><span>总返佣</span><b>{{ money(person.total_commission_amount) }}</b></div>
        <div class="row"><span>业务员佣金</span><b>{{ money(person.agent_commission_amount) }}</b></div>
      </template>
      <div class="row"><span>保单</span><b>{{ person.policy_no || '尚未出单' }}</b></div>
      <div class="row"><span>状态</span><b>{{ statusText[person.status] }}</b></div>

      <div class="history-section">
        <div class="history-title">参保历史</div>
        <el-table v-loading="historyLoading" :data="history" size="small" empty-text="暂无参保记录">
          <el-table-column label="保单号" prop="policy_no" min-width="120" />
          <el-table-column label="产品">
            <template #default="{ row }">{{ row.insurer }} · {{ row.plan_name }}</template>
          </el-table-column>
          <el-table-column label="生效时间" min-width="140">
            <template #default="{ row }">{{ formatDateTime(row.effective_at) }}</template>
          </el-table-column>
          <el-table-column label="终止时间" min-width="140">
            <template #default="{ row }">{{ row.terminated_at ? formatDateTime(row.terminated_at) : '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '保障中' : '已结束' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('edit')">编辑资料</el-button>
      <el-button :type="person?.status === 'active' ? 'danger' : 'success'" @click="emit('toggle-status')">
        {{ person?.status === 'active' ? '办理停保' : '办理参保' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.detail-grid {
  display: grid;
  gap: 8px;
}
.row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.row span {
  color: var(--el-text-color-secondary);
}
.history-section {
  margin-top: 16px;
}
.history-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
</style>
