<script setup lang="ts">
import { ref } from 'vue'
import { api, yuan, when } from '../../api'
import { zhStatus, statusTone } from '../../i18n'

/**
 * 借支管理(老系统 M8「借押保」)。
 *
 * <p>借支是**平台先把钱垫给工人**,之后从工资里逐笔扣回。所以界面上要一直看得见
 * "还欠多少" —— 只显示"借了多少"的话,还了一半的单子和一分没还的看起来一样。
 */

const workerId = ref('')
const list = ref<any[]>([])
const loading = ref(false)
const err = ref(''); const msg = ref('')
const busy = ref('')

const amount = ref('')
const reason = ref('')

/** 展开中的还款明细。争议时这是唯一说得清的东西。 */
const openId = ref<number | null>(null)
const repayments = ref<Record<number, any[]>>({})

async function load() {
  if (!workerId.value.trim()) { err.value = '请先填工人编号'; return }
  loading.value = true; err.value = ''
  try { list.value = await api(`/api/fund/advances/worker/${workerId.value.trim()}`) }
  catch (e: any) { err.value = e.message; list.value = [] }
  finally { loading.value = false }
}

async function grant() {
  msg.value = ''; err.value = ''; busy.value = 'grant'
  try {
    const r = await api<{ id: number }>('/api/fund/advances', { body: {
      workerUserId: Number(workerId.value),
      amountCents: Math.round(Number(amount.value) * 100),
      reason: reason.value.trim(),
    } })
    msg.value = `借支 #${r.id} 已批。将在下次发工资时自动抵扣`
    amount.value = ''; reason.value = ''
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function repay(id: number) {
  const v = prompt('登记线下还款，金额（元）：')
  if (!v?.trim()) return
  msg.value = ''; err.value = ''; busy.value = `repay-${id}`
  try {
    await api(`/api/fund/advances/${id}/repayments`, {
      body: { amountCents: Math.round(Number(v) * 100) } })
    msg.value = `借支 #${id} 已登记还款`
    delete repayments.value[id]
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function cancel(id: number) {
  if (!confirm(`撤销借支 #${id}？只有一分钱都没还过的才能撤销。`)) return
  msg.value = ''; err.value = ''; busy.value = `cancel-${id}`
  try {
    await api(`/api/fund/advances/${id}/cancel`, { method: 'PUT' })
    msg.value = `借支 #${id} 已撤销`
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function toggle(id: number) {
  if (openId.value === id) { openId.value = null; return }
  openId.value = id
  if (repayments.value[id]) return
  try { repayments.value[id] = await api(`/api/fund/advances/${id}/repayments`) }
  catch (e: any) { err.value = e.message }
}

const outstanding = () => list.value
  .filter(a => a.status === 'ACTIVE')
  .reduce((s, a) => s + a.outstandingCents, 0)
</script>

<template>
  <h1>借支管理</h1>
  <p class="sub">平台先垫钱给工人，<b>发工资时自动从工资里抵扣</b>，先借的先还</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <div class="row" style="align-items:flex-end;gap:8px">
      <div class="field" style="flex:0 0 220px">
        <label>工人编号</label>
        <input v-model="workerId" placeholder="如：42" @keyup.enter="load" />
      </div>
      <button @click="load">查询</button>
    </div>
  </div>

  <div class="card" v-if="list.length">
    <h3>当前未还合计</h3>
    <div style="font-size:26px;font-weight:650;color:var(--primary);font-family:var(--mono)">
      {{ yuan(outstanding()) }}
    </div>
    <p class="hint" style="margin-bottom:0">
      下次发工资时会从应发里扣掉这些。<b>扣不完的留到再下次，不会产生负工资。</b>
    </p>
  </div>

  <div class="card">
    <h3>借支记录</h3>
    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!list.length" class="empty">这个工人没有借支记录</div>
    <table v-else>
      <thead>
        <tr><th style="width:70px">编号</th><th style="width:110px">本金</th>
            <th style="width:110px">未还</th><th style="width:90px">状态</th>
            <th>事由</th><th style="width:150px">时间</th><th style="width:190px"></th></tr>
      </thead>
      <tbody>
        <template v-for="a in list" :key="a.id">
          <tr>
            <td>#{{ a.id }}</td>
            <td style="font-family:var(--mono)">{{ yuan(a.amountCents) }}</td>
            <td style="font-family:var(--mono)"
                :style="{ color: a.outstandingCents > 0 ? 'var(--warn,#eab308)' : undefined }">
              {{ yuan(a.outstandingCents) }}
            </td>
            <td><span class="tag" :class="statusTone(a.status)">{{ zhStatus(a.status) }}</span></td>
            <td style="color:var(--muted)">{{ a.reason || '—' }}</td>
            <td style="color:var(--muted);font-size:12.5px">{{ when(a.createdAt) }}</td>
            <td>
              <div class="row" style="gap:6px">
                <button v-if="a.status === 'ACTIVE'" class="sm"
                        :disabled="busy === `repay-${a.id}`" @click="repay(a.id)">登记还款</button>
                <button v-if="a.status === 'ACTIVE' && a.outstandingCents === a.amountCents"
                        class="ghost sm" :disabled="busy === `cancel-${a.id}`"
                        @click="cancel(a.id)">撤销</button>
                <button class="ghost sm" @click="toggle(a.id)">明细</button>
              </div>
            </td>
          </tr>
          <tr v-if="openId === a.id">
            <td colspan="7" style="background:var(--surface-2);padding:12px;font-size:12.5px">
              <div v-if="!repayments[a.id]?.length" style="color:var(--muted)">还没有还款记录</div>
              <div v-for="r in repayments[a.id]" :key="r.id" style="padding:2px 0">
                {{ when(r.createdAt) }} ·
                {{ r.source === 'SALARY_DEDUCTION' ? '工资抵扣' : '线下还款' }}
                <b>{{ yuan(r.amountCents) }}</b>
                <span v-if="r.settlementId" style="color:var(--muted)">（结算单 #{{ r.settlementId }}）</span>
              </div>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>

  <div class="card" v-if="workerId">
    <h3>批一笔借支</h3>
    <p class="hint">
      额度上限在「参数设置」里配，<b>连同已欠的一起算</b>——不然借十次小额就绕过去了。
    </p>
    <div class="row">
      <div class="field" style="flex:0 0 160px"><label>金额（元）</label>
        <input v-model="amount" placeholder="如：500" /></div>
      <div class="field"><label>事由<span style="color:var(--bad)">*</span></label>
        <input v-model="reason" placeholder="必填，如：家中急用 / 购置工具" /></div>
    </div>
    <button :disabled="!amount || !reason.trim() || busy === 'grant'" @click="grant">批准借支</button>
  </div>
</template>
