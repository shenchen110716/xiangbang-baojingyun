<script setup lang="ts">
/**
 * 机构端「资金与代发」。
 *
 * <p>老板 2026-08-06 选了乙:**机构自己充值、自己发薪**。
 * 在此之前资金账户是全平台共用一个,这个页面根本没法存在 ——
 * 那时"我的余额"这个概念不成立。
 */
import { ref, computed, onMounted } from 'vue'
import { api, when, yuan } from '../../api'
import { zhStatus, statusTone } from '../../i18n'

const orgs = ref<any[]>([])
const orgId = ref('')
const balance = ref(0)
const payouts = ref<any[]>([])
const loading = ref(false)
const busy = ref('')
const msg = ref(''); const err = ref('')

const topUpYuan = ref('')
/** 幂等键让运营自己填，**不自动生成** —— 见下面的说明。 */
const topUpKey = ref('')
const topUpReason = ref('')

const currentOrg = computed(() => orgs.value.find(o => String(o.id) === orgId.value))

async function loadOrgs() {
  loading.value = true; err.value = ''
  try {
    orgs.value = (await api<any[]>('/api/org/mine')).filter(o => o.status === 'APPROVED')
    if (!orgId.value && orgs.value.length) orgId.value = String(orgs.value[0].id)
    if (orgId.value) await load()
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

async function load() {
  if (!orgId.value) return
  err.value = ''
  try {
    const b = await api<{ balance: number }>(`/api/fund/accounts/org/${orgId.value}/USER_FUNDS`)
    balance.value = b.balance
    payouts.value = await api(`/api/fund/payouts/org/${orgId.value}`)
  } catch (e: any) { err.value = e.message }
}

async function topUp() {
  const cents = Math.round(Number(topUpYuan.value) * 100)
  if (!cents || cents <= 0) { err.value = '请填写正的充值金额'; return }
  if (!topUpKey.value.trim()) { err.value = '请填写幂等键'; return }
  msg.value = ''; err.value = ''; busy.value = 'topup'
  try {
    await api(`/api/fund/accounts/org/${orgId.value}/USER_FUNDS/top-up`, { body: {
      amountCents: cents,
      reason: topUpReason.value.trim() || '机构充值',
      idempotencyKey: topUpKey.value.trim() } })
    msg.value = `已入账 ${yuan(cents)}`
    topUpYuan.value = ''; topUpKey.value = ''; topUpReason.value = ''
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function disburse(id: number) {
  msg.value = ''; err.value = ''; busy.value = `d-${id}`
  try {
    await api(`/api/fund/payouts/${id}/disburse`, { method: 'PUT' })
    msg.value = `代发 #${id} 已提交`
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

onMounted(loadOrgs)
</script>

<template>
  <h1>资金与代发</h1>

  <div class="card note">
    <h3>钱从你自己的账户出</h3>
    <p class="hint" style="margin-bottom:0">
      2026-08-06 起资金账户<b>按单位分账</b>：这里的余额只属于你选的这家单位，
      发薪只能扣这一家的。<b>平台账户里的钱不能用来发你的薪</b>——
      余额不足时代发会被直接拒绝，而不是从别处挪。
    </p>
  </div>

  <div v-if="err" class="error">{{ err }}</div>
  <div v-if="msg" class="ok">{{ msg }}</div>
  <div v-if="loading" class="empty">加载中…</div>

  <div v-else-if="!orgs.length" class="card">
    <div class="empty">你名下还没有已审核的单位。先去「我的组织」提交并等平台审核。</div>
  </div>

  <template v-else>
    <div class="card">
      <div class="row">
        <div class="field" style="flex:0 0 280px"><label>单位</label>
          <select v-model="orgId" @change="load">
            <option v-for="o in orgs" :key="o.id" :value="String(o.id)">
              {{ o.name }}（#{{ o.id }}）
            </option>
          </select>
        </div>
        <div class="field">
          <label>账户余额</label>
          <div style="font-size:28px;font-weight:600;padding-top:4px">{{ yuan(balance) }}</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>充值</h3>
      <div class="row">
        <div class="field" style="flex:0 0 160px"><label>金额（元）</label>
          <input v-model="topUpYuan" placeholder="如：50000" /></div>
        <div class="field"><label>事由</label>
          <input v-model="topUpReason" placeholder="如：8 月工资备资" /></div>
        <div class="field" style="flex:0 0 220px"><label>幂等键<span style="color:var(--bad)">*</span></label>
          <input v-model="topUpKey" placeholder="如：银行流水号" /></div>
        <button style="align-self:flex-end"
                :disabled="!topUpYuan || !topUpKey.trim() || busy === 'topup'"
                @click="topUp">入账</button>
      </div>
      <p class="hint" style="margin-bottom:0">
        <b>幂等键要你自己填，界面不替你生成。</b>
        它的用处是「同一笔钱重复提交只入账一次」——自动生成的话每次点都是新键，
        网络重试或手滑点两下就<b>凭空多一笔钱</b>，而那要对账才发现。
        填银行流水号最稳妥：同一笔转账天然只有一个。
      </p>
    </div>

    <div class="card">
      <h3>{{ currentOrg?.name }} 的代发单</h3>
      <div v-if="!payouts.length" class="empty">还没有待付的代发单</div>
      <table v-else>
        <thead><tr><th style="width:80px">编号</th><th style="width:110px">收款人</th>
                   <th style="width:120px">金额</th><th style="width:100px">状态</th>
                   <th style="width:120px"></th></tr></thead>
        <tbody>
          <tr v-for="p in payouts" :key="p.id">
            <td>#{{ p.id }}</td>
            <td>用户 #{{ p.payeeUserId }}</td>
            <td>{{ yuan(p.amountCents) }}</td>
            <td><span class="tag" :class="statusTone(p.status)">{{ zhStatus(p.status) }}</span></td>
            <td>
              <button v-if="p.status === 'PENDING'" class="sm"
                      :disabled="busy === `d-${p.id}`" @click="disburse(p.id)">发放</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="hint" style="margin-bottom:0">
        余额不足时发放会被拒绝，<b>不会从平台账户垫付</b>。先充值再发。
      </p>
    </div>
  </template>
</template>
