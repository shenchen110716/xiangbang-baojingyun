<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, yuan } from '../../api'
import { zhStatus, statusTone } from '../../i18n'

const id = ref(''); const acct = ref('USER_FUNDS')
const payout = ref<any>(null); const balance = ref<number | null>(null)
const err = ref(''); const msg = ref(''); const busy = ref('')

async function run(k: string, fn: () => Promise<any>, ok?: string) {
  msg.value = ''; err.value = ''; busy.value = k
  try { const r = await fn(); if (ok) msg.value = ok; return r }
  catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}
const look = () => run('look', async () => payout.value = await api(`/api/fund/payouts/${id.value}`))
const disburse = () => run('pay', async () => {
  await api(`/api/fund/payouts/${id.value}/disburse`, { method: 'PUT' })
  payout.value = await api(`/api/fund/payouts/${id.value}`)
}, '已发放（mock，钱没真的动）')
const retry = () => run('retry', async () => {
  await api(`/api/fund/payouts/${id.value}/retry`, { method: 'PUT' })
  payout.value = await api(`/api/fund/payouts/${id.value}`)
}, '已重试')
const bal = () => run('bal', async () => balance.value = (await api<any>(`/api/fund/accounts/${acct.value}`)).balanceCents)

const topUpAmount = ref('1000')
const topUp = () => run('top', async () => {
  // 幂等键由前端生成并在这一次操作里固定:网络超时后重试同一个键,钱只会加一次。
  // 不带键的入账重试一次就多一笔,而账面上看不出异常。
  const key = `web-topup-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const r = await api<any>(`/api/fund/accounts/${acct.value}/top-up`, { body: {
    amountCents: Math.round(Number(topUpAmount.value) * 100), reason: '平台手工入账', idempotencyKey: key } })
  balance.value = r.balanceCents
}, '已入账')
onMounted(bal)
</script>

<template>
  <h1>资金与代发</h1>
  <p class="sub">资金域是全系统唯一能动钱的地方。放款要「平台运维」角色</p>

  <div class="card note">
    <h3>代发是 mock 的</h3>
    <p class="hint" style="margin:0">
      点「发放」会扣监管账户、标 SUCCESS、写假的完税凭证号、通知工人「工资已发放」——<strong>而钱一分没出去</strong>。
    </p>
  </div>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <h3>账户余额</h3>
    <div class="row">
      <div class="field" style="flex:0 0 240px"><label>账户</label>
        <select v-model="acct" @change="bal">
          <option value="USER_FUNDS">在途资金</option>
          <option value="PLATFORM_REVENUE">平台收入</option>
          <option value="GUARANTEE_POOL">担保资金池</option>
        </select>
      </div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="busy === 'bal'" @click="bal">查询</button></div>
      <div class="field" style="flex:none;padding-bottom:10px">
        <span class="tag ok" style="font-size:14px">{{ balance === null ? '…' : yuan(balance) }}</span>
      </div>
    </div>

    <div class="row" style="border-top:1px solid var(--border);padding-top:14px;margin-top:4px">
      <div class="field" style="flex:0 0 160px"><label>入账金额（元）</label><input v-model="topUpAmount" /></div>
      <div class="field" style="flex:none"><button :disabled="!topUpAmount || busy === 'top'" @click="topUp">入账</button></div>
      <div class="field" style="padding-bottom:10px;color:var(--muted);font-size:12.5px">
        监管账户没钱时放款会一直报「余额不足」。入账带幂等键，重复点不会多加。
      </div>
    </div>
  </div>

  <div class="card">
    <h3>处理代发单</h3>
    <div class="row">
      <div class="field" style="flex:0 0 160px"><label>代发单 ID</label><input v-model="id" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!id || busy === 'look'" @click="look">查询</button></div>
      <div class="field" style="flex:none"><button :disabled="!id || busy === 'pay'" @click="disburse">发放</button></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!id || busy === 'retry'" @click="retry">重试</button></div>
    </div>
    <div v-if="payout" class="row" style="margin-bottom:8px">
      <span class="tag">金额 {{ yuan(payout.amountCents) }}</span>
      <span class="tag">收款人 #{{ payout.payeeUserId }}</span>
      <span class="tag" :class="statusTone(payout.status)">{{ zhStatus(payout.status) }}</span>
    </div>
  </div>
</template>
