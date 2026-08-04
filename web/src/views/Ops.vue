<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const domain = ref(''); const eventId = ref('')
const stuck = useAction(() => api<any[]>('/api/ops/outbox/stuck'))
const replay = useAction(() => api(`/api/ops/outbox/${domain.value}/${eventId.value}/replay`, { method: 'POST' }))
</script>

<template>
  <h1>事件投递监控</h1>
  <p class="sub">跨域事件的投递状态。卡住的事件意味着<strong>有人的工资单没生成、佣金没入账</strong>，不会自愈</p>

  <div class="card">
    <h3>卡死事件</h3>
    <p class="hint">需要「平台运维」角色，普通账号会被拒绝</p>
    <button class="ghost" @click="stuck.run()">查询</button>
    <div style="height:12px"></div>
    <div v-if="Array.isArray(stuck.data.value) && !stuck.data.value.length" class="msg ok">没有卡死事件</div>
    <Result :loading="stuck.loading.value" :error="stuck.error.value" :data="stuck.data.value" />
  </div>

  <div class="card">
    <h3>人工重放</h3>
    <div class="row">
      <div class="field"><label>域</label><input v-model="domain" placeholder="如 settlement" /></div>
      <div class="field"><label>事件 ID</label><input v-model="eventId" /></div>
      <div class="field" style="flex:none"><button :disabled="!domain || !eventId" @click="replay.run()">重放</button></div>
    </div>
    <Result :loading="replay.loading.value" :error="replay.error.value" :data="replay.done.value ? '' : null" ok-text="已重放" />
  </div>
</template>
