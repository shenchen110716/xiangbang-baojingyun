<script setup lang="ts">
import { ref } from 'vue'
import { api, yuan } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const id = ref(''); const acct = ref('USER_FUNDS')
const load = useAction(() => api(`/api/fund/payouts/${id.value}`))
const disburse = useAction(() => api(`/api/fund/payouts/${id.value}/disburse`, { method: 'PUT' }))
const retry = useAction(() => api(`/api/fund/payouts/${id.value}/retry`, { method: 'PUT' }))
const detail = useAction(() => api(`/api/fund/payouts/${id.value}/disbursement`))
const balance = useAction(() => api(`/api/fund/accounts/${acct.value}`))
</script>

<template>
  <h1>资金与代发</h1>
  <p class="sub">资金域是全系统<strong>唯一</strong>能动钱的地方，其它域都要调它</p>

  <div class="card" style="background:#fdf3e0;border-color:#f2e2c4">
    <h3 style="color:#94661a">代发是 mock 的</h3>
    <p class="hint" style="margin:0;color:#94661a">
      点「发放」会扣减监管账户、把状态标成 SUCCESS、写入假的完税凭证号、给工人发「工资已发放」通知——
      <strong>而钱一分没出去</strong>。这是刻意做成显式开关的，不能靠上线前记得换。
    </p>
  </div>

  <div class="card">
    <h3>代发单</h3>
    <div class="row">
      <div class="field"><label>代发单 ID</label><input v-model="id" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!id" @click="load.run()">查询</button></div>
      <div class="field" style="flex:none"><button :disabled="!id" @click="disburse.run()">发放</button></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!id" @click="retry.run()">重试</button></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!id" @click="detail.run()">发放详情</button></div>
    </div>
    <div v-if="load.data.value" class="row" style="margin-bottom:10px">
      <span class="tag">金额 {{ yuan((load.data.value as any).amountCents) }}</span>
      <span class="tag">{{ (load.data.value as any).status }}</span>
    </div>
    <Result :loading="load.loading.value" :error="load.error.value" :data="load.data.value" />
    <Result :loading="disburse.loading.value" :error="disburse.error.value" :data="disburse.done.value ? '' : null" ok-text="已发放（mock）" />
    <Result :loading="retry.loading.value" :error="retry.error.value" :data="retry.done.value ? '' : null" ok-text="已重试" />
    <Result :loading="detail.loading.value" :error="detail.error.value" :data="detail.data.value" />
  </div>

  <div class="card">
    <h3>账户余额</h3>
    <div class="row">
      <div class="field" style="flex:0 0 210px"><label>账户类型</label>
        <select v-model="acct">
          <option value="USER_FUNDS">在途资金 USER_FUNDS</option>
          <option value="PLATFORM_REVENUE">平台收入 PLATFORM_REVENUE</option>
          <option value="GUARANTEE_POOL">担保资金池 GUARANTEE_POOL</option>
        </select>
      </div>
      <div class="field" style="flex:none"><button class="ghost" @click="balance.run()">查询</button></div>
    </div>
    <div v-if="balance.data.value" class="row" style="margin-bottom:10px">
      <span class="tag ok">余额 {{ yuan((balance.data.value as any).balanceCents) }}</span>
    </div>
    <Result :loading="balance.loading.value" :error="balance.error.value" :data="balance.data.value" />
  </div>
</template>
