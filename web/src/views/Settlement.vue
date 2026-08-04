<script setup lang="ts">
import { ref } from 'vue'
import { api, yuan } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const id = ref(''); const reason = ref('')
const load = useAction(() => api(`/api/settlement/${id.value}`))
const voidIt = useAction(() => api(`/api/settlement/${id.value}/void`,
  { method: 'PUT', body: { reason: reason.value.trim() } }))
</script>

<template>
  <h1>结算</h1>
  <p class="sub">履约完成后自动生成工资单，一笔结算只会生成一张</p>

  <div class="card">
    <h3>查询工资单</h3>
    <div class="row">
      <div class="field"><label>结算 ID</label><input v-model="id" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!id" @click="load.run()">查询</button></div>
    </div>
    <div v-if="load.data.value" class="row" style="margin-bottom:10px">
      <span class="tag ok">应发 {{ yuan((load.data.value as any).amountCents) }}</span>
      <span class="tag">{{ (load.data.value as any).status }}</span>
    </div>
    <Result :loading="load.loading.value" :error="load.error.value" :data="load.data.value" />
  </div>

  <div class="card">
    <h3>作废结算</h3>
    <p class="hint">已发放的结算<strong>不能</strong>被静默作废，只能走冲正 —— 系统会直接拒绝</p>
    <div class="row">
      <div class="field"><label>作废原因</label><input v-model="reason" placeholder="必填" /></div>
      <div class="field" style="flex:none">
        <button class="danger" :disabled="!id || !reason" @click="voidIt.run()">作废</button>
      </div>
    </div>
    <Result :loading="voidIt.loading.value" :error="voidIt.error.value" :data="voidIt.done.value ? '' : null" ok-text="结算已作废" />
  </div>
</template>
