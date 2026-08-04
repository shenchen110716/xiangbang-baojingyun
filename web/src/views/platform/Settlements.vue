<script setup lang="ts">
import { ref } from 'vue'
import { api, yuan } from '../../api'
import { zhStatus, statusTone } from '../../i18n'

const id = ref(''); const reason = ref('')
const s = ref<any>(null); const err = ref(''); const msg = ref(''); const busy = ref('')

async function look() {
  err.value = ''; msg.value = ''; busy.value = 'look'
  try { s.value = await api(`/api/settlement/${id.value}`) } catch (e: any) { err.value = e.message } finally { busy.value = '' }
}
async function voidIt() {
  err.value = ''; msg.value = ''; busy.value = 'void'
  try {
    await api(`/api/settlement/${id.value}/void`, { method: 'PUT', body: { reason: reason.value.trim() } })
    msg.value = `结算 #${id.value} 已作废`
    s.value = await api(`/api/settlement/${id.value}`)
  } catch (e: any) { err.value = e.message } finally { busy.value = '' }
}
</script>

<template>
  <h1>结算处理</h1>
  <p class="sub">已发放的结算<strong>不能</strong>被静默作废，只能走冲正——系统会直接拒绝</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <div class="row">
      <div class="field" style="flex:0 0 160px"><label>结算 ID</label><input v-model="id" /></div>
      <div class="field"><label>作废原因</label><input v-model="reason" placeholder="作废时必填" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!id || busy === 'look'" @click="look">查询</button></div>
      <div class="field" style="flex:none"><button class="danger" :disabled="!id || !reason || busy === 'void'" @click="voidIt">作废</button></div>
    </div>
    <div v-if="s" class="row">
      <span class="tag">应发 {{ yuan(s.amountCents) }}</span>
      <span class="tag">工人 #{{ s.workerUserId }}</span>
      <span class="tag" :class="statusTone(s.status)">{{ zhStatus(s.status) }}</span>
      <span v-if="s.voidReason" class="tag warn">{{ s.voidReason }}</span>
    </div>
  </div>
</template>
