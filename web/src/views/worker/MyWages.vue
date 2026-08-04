<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, yuan } from '../../api'

const settlements = ref<any[]>([])
const payouts = ref<any[]>([])
const loading = ref(true)
const err = ref('')

async function load() {
  loading.value = true; err.value = ''
  try {
    settlements.value = await api('/api/settlement/mine')
    payouts.value = await api('/api/fund/payouts/mine')
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}
const total = () => payouts.value.filter(p => p.status === 'PAID')
  .reduce((s, p) => s + (p.amountCents || 0), 0)
onMounted(load)
</script>

<template>
  <h1>我的工资</h1>
  <p class="sub">履约完成后自动生成工资单，平台放款后进入发放记录</p>

  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card" style="background:#fdf3e0;border-color:#f2e2c4">
    <p class="hint" style="margin:0;color:#94661a">
      测试环境：代发通道是 mock，「已发放」不代表钱真的到账。
    </p>
  </div>

  <div class="grid">
    <div class="card">
      <h3>累计已发放</h3>
      <div style="font-size:26px;font-weight:600;color:var(--primary-dark)">{{ yuan(total()) }}</div>
    </div>
    <div class="card">
      <h3>工资单</h3>
      <div v-if="loading" class="msg info">加载中…</div>
      <div v-else-if="!settlements.length" class="empty">还没有工资单</div>
      <table v-else>
        <thead><tr><th>结算单</th><th>金额</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="s in settlements" :key="s.id">
            <td>#{{ s.id }}</td><td>{{ yuan(s.amountCents) }}</td>
            <td><span class="tag" :class="s.status === 'VOIDED' ? 'bad' : 'ok'">{{ s.status }}</span></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="card">
    <h3>发放记录</h3>
    <div v-if="!payouts.length && !loading" class="empty">还没有发放记录</div>
    <table v-else-if="payouts.length">
      <thead><tr><th>发放单</th><th>对应结算</th><th>金额</th><th>状态</th></tr></thead>
      <tbody>
        <tr v-for="p in payouts" :key="p.id">
          <td>#{{ p.id }}</td><td>#{{ p.settlementId }}</td><td>{{ yuan(p.amountCents) }}</td>
          <td><span class="tag" :class="p.status === 'PAID' ? 'ok' : p.status === 'FAILED' ? 'bad' : 'warn'">{{ p.status }}</span></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
