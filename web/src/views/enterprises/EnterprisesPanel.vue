<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as enterprisesApi from '@/api/enterprises'
import * as agentsApi from '@/api/agents'
import { getDashboard } from '@/api/dashboard'
import type { Enterprise, Agent } from '@/api/types'
import { money } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import DetailModal from '@/components/DetailModal.vue'
import StatTile from '@/components/StatTile.vue'

const loading = ref(true)
const list = ref<Enterprise[]>([])
const agents = ref<Agent[]>([])
const search = ref('')
const statusFilter = ref('')
const alertCount = ref(0)

async function load() {
  loading.value = true
  try {
    const [rows, dashboard] = await Promise.all([
      enterprisesApi.listEnterprises({ q: search.value || undefined, status: statusFilter.value || undefined }),
      getDashboard(),
    ])
    list.value = rows
    alertCount.value = new Set(dashboard.balance_alerts.map((a) => a.enterprise_id)).size
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  agents.value = await agentsApi.listAgents()
  load()
})

const pendingCount = computed(() => list.value.filter((x) => x.status === 'pending').length)

// ---- create / edit ----
const formVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  name: '', kind: '企业', credit_code: '', contact: '', phone: '',
  agent_id: null as number | null, usage_fee_daily: 0.1, alert_days: 3,
})
function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', kind: '企业', credit_code: '', contact: '', phone: '', agent_id: null, usage_fee_daily: 0.1, alert_days: 3 })
  formVisible.value = true
}
function openEdit(item: Enterprise) {
  editingId.value = item.id
  Object.assign(form, { name: item.name, kind: item.kind, credit_code: item.credit_code, contact: item.contact, phone: item.phone, agent_id: item.agent_id, usage_fee_daily: item.usage_fee_daily, alert_days: item.alert_days })
  formVisible.value = true
}
async function submitForm() {
  if (!form.name || !form.contact || !form.phone) { ElMessage.error('请填写单位名称、联系人、联系电话'); return }
  try {
    if (editingId.value) await enterprisesApi.updateEnterprise(editingId.value, form)
    else await enterprisesApi.createEnterprise(form)
    ElMessage.success('保存成功')
    formVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- recharge ----
const rechargeVisible = ref(false)
const rechargeTarget = ref<Enterprise | null>(null)
const rechargeForm = reactive({ account: 'premium' as 'premium' | 'usage', amount: 0 })
function openRecharge(item: Enterprise) {
  rechargeTarget.value = item
  Object.assign(rechargeForm, { account: 'premium', amount: 0 })
  rechargeVisible.value = true
}
async function submitRecharge() {
  if (!rechargeTarget.value || rechargeForm.amount < 0.01) { ElMessage.error('请输入充值金额'); return }
  try {
    await enterprisesApi.rechargeEnterprise(rechargeTarget.value.id, rechargeForm.account, rechargeForm.amount)
    ElMessage.success('充值成功')
    rechargeVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- admins ----
const adminsVisible = ref(false)
const adminsTarget = ref<Enterprise | null>(null)
const adminsList = ref<Array<{ id: number; username: string; name: string; phone: string; active: boolean }>>([])
const newAdmin = reactive({ username: '', password: '123456', name: '', phone: '' })
async function openAdmins(item: Enterprise) {
  adminsTarget.value = item
  Object.assign(newAdmin, { username: '', password: '123456', name: '', phone: '' })
  adminsVisible.value = true
  adminsList.value = await enterprisesApi.listEnterpriseAdmins(item.id)
}
async function submitNewAdmin() {
  if (!adminsTarget.value || !newAdmin.username || !newAdmin.password || !newAdmin.name) { ElMessage.error('请填写账号、密码、姓名'); return }
  try {
    await enterprisesApi.createEnterpriseAdmin(adminsTarget.value.id, { ...newAdmin })
    ElMessage.success('管理员账号已创建')
    adminsList.value = await enterprisesApi.listEnterpriseAdmins(adminsTarget.value.id)
    Object.assign(newAdmin, { username: '', password: '123456', name: '', phone: '' })
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- products (read-only) ----
const productsVisible = ref(false)
const productsList = ref<any[]>([])
async function openProducts(item: Enterprise) {
  productsVisible.value = true
  productsList.value = await enterprisesApi.listEnterpriseProducts(item.id)
}

async function removeEnterprise(item: Enterprise) {
  try {
    await ElMessageBox.confirm(`确定删除投保单位「${item.name}」吗？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await enterprisesApi.deleteEnterprise(item.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="enterprises-panel">
    <div class="stat-grid">
      <StatTile label="投保单位总数" :value="list.length" />
      <StatTile label="待审核单位" :value="pendingCount" hint-type="warning" :hint="pendingCount > 0 ? '需跟进' : ''" />
      <StatTile label="余额预警单位" :value="alertCount" hint-type="danger" :hint="alertCount > 0 ? '需充值' : ''" />
    </div>

    <PageCard title="投保单位列表" :count="list.length">
      <template #actions>
        <el-button type="primary" @click="openCreate">＋ 新增投保单位</el-button>
      </template>
      <div class="filter-row">
        <FilterBar v-model:search="search" @search="load">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
            <el-option label="待核验" value="pending" />
            <el-option label="已核验" value="approved" />
          </el-select>
        </FilterBar>
      </div>
      <el-table :data="list" size="small">
        <el-table-column prop="name" label="单位名称" min-width="160" />
        <el-table-column prop="kind" label="类型" width="100" />
        <el-table-column prop="contact" label="联系人" width="110" />
        <el-table-column prop="agent_name" label="负责业务员" width="110">
          <template #default="{ row }">{{ row.agent_name || '未指定' }}</template>
        </el-table-column>
        <el-table-column label="保费账户" width="110">
          <template #default="{ row }">{{ money(row.premium_balance) }}</template>
        </el-table-column>
        <el-table-column label="服务费账户" width="110">
          <template #default="{ row }">{{ money(row.usage_balance) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'approved' ? 'success' : 'warning'" size="small">
              {{ row.status === 'approved' ? '已核验' : '待核验' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openRecharge(row)">充值</el-button>
            <el-button link type="primary" size="small" @click="openAdmins(row)">管理员</el-button>
            <el-button link type="primary" size="small" @click="openProducts(row)">参保产品</el-button>
            <el-button link type="danger" size="small" @click="removeEnterprise(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑投保单位' : '新增投保单位'" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="单位名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="单位类型">
          <el-select v-model="form.kind" style="width: 100%">
            <el-option label="企业" value="企业" />
            <el-option label="HR机构" value="HR机构" />
          </el-select>
        </el-form-item>
        <el-form-item label="统一社会信用代码"><el-input v-model="form.credit_code" /></el-form-item>
        <el-form-item label="联系人" required><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="联系电话" required><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="负责业务员">
          <el-select v-model="form.agent_id" clearable placeholder="暂不指定" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日均使用费">
          <el-input-number v-model="form.usage_fee_daily" :min="0" :step="0.1" />
        </el-form-item>
        <el-form-item label="余额预警天数">
          <el-input-number v-model="form.alert_days" :min="3" :max="7" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rechargeVisible" title="账户充值" width="420px">
      <el-form v-if="rechargeTarget" :model="rechargeForm" label-width="90px">
        <el-form-item label="投保单位"><span>{{ rechargeTarget.name }}</span></el-form-item>
        <el-form-item label="充值账户">
          <el-select v-model="rechargeForm.account" style="width: 100%">
            <el-option label="保费账户" value="premium" />
            <el-option label="服务费账户" value="usage" />
          </el-select>
        </el-form-item>
        <el-form-item label="充值金额">
          <el-input-number v-model="rechargeForm.amount" :min="0.01" :step="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRecharge">确认充值</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="adminsVisible" title="管理员账号" width="560px">
      <el-table :data="adminsList" size="small" style="margin-bottom: 16px">
        <el-table-column prop="username" label="账号" />
        <el-table-column prop="name" label="姓名" />
        <el-table-column prop="phone" label="手机号" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-form :model="newAdmin" label-width="90px">
        <el-form-item label="登录账号" required><el-input v-model="newAdmin.username" /></el-form-item>
        <el-form-item label="初始密码" required><el-input v-model="newAdmin.password" /></el-form-item>
        <el-form-item label="姓名" required><el-input v-model="newAdmin.name" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="newAdmin.phone" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adminsVisible = false">关闭</el-button>
        <el-button type="primary" @click="submitNewAdmin">新增管理员</el-button>
      </template>
    </el-dialog>

    <DetailModal v-model="productsVisible" title="参保产品">
      <el-table :data="productsList" size="small">
        <el-table-column prop="insurer" label="保司" />
        <el-table-column prop="product" label="产品" />
        <el-table-column prop="insured_count" label="在保人数" />
        <el-table-column label="保费">
          <template #default="{ row }">{{ money(row.premium_total) }}</template>
        </el-table-column>
      </el-table>
    </DetailModal>
  </div>
</template>

<style scoped>
.enterprises-panel {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.filter-row {
  padding: 0 20px 14px;
}
</style>
