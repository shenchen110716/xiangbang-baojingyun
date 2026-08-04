<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const jobId = ref(''); const id = ref('')
const apply = useAction(() => api<{ id: number }>(`/api/engagement/${jobId.value}/apply`, { method: 'POST' }))
const load = useAction(() => api(`/api/engagement/${id.value}`))
const act = (what: string) => useAction(() => api(`/api/engagement/${id.value}/${what}`, { method: 'PUT' }))
const accept = act('accept'); const reject = act('reject'); const complete = act('complete')
</script>

<template>
  <h1>报名与录用</h1>
  <p class="sub">报名 → 录用（占名额）→ 签协议 → 完成履约。这是唯一有权改履约状态的域</p>

  <div class="card">
    <h3>我要报名</h3>
    <p class="hint">同一人对同一岗位只能报一次；未实名会被拒绝</p>
    <div class="row">
      <div class="field"><label>岗位 ID</label><input v-model="jobId" /></div>
      <div class="field" style="flex:none"><button :disabled="!jobId" @click="apply.run()">报名</button></div>
    </div>
    <Result :loading="apply.loading.value" :error="apply.error.value" :data="apply.data.value" ok-text="报名成功" />
  </div>

  <div class="card">
    <h3>履约单操作</h3>
    <p class="hint">录用/拒绝由用工方做；完成履约要求协议已签署</p>
    <div class="row">
      <div class="field"><label>履约单 ID</label><input v-model="id" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!id" @click="load.run()">查询</button></div>
      <div class="field" style="flex:none"><button :disabled="!id" @click="accept.run()">录用</button></div>
      <div class="field" style="flex:none"><button class="danger" :disabled="!id" @click="reject.run()">拒绝</button></div>
      <div class="field" style="flex:none"><button :disabled="!id" @click="complete.run()">完成履约</button></div>
    </div>
    <Result :loading="load.loading.value" :error="load.error.value" :data="load.data.value" />
    <Result :loading="accept.loading.value" :error="accept.error.value" :data="accept.done.value ? '' : null" ok-text="已录用" />
    <Result :loading="reject.loading.value" :error="reject.error.value" :data="reject.done.value ? '' : null" ok-text="已拒绝" />
    <Result :loading="complete.loading.value" :error="complete.error.value" :data="complete.done.value ? '' : null" ok-text="履约已完成，结算会自动生成" />
  </div>
</template>
