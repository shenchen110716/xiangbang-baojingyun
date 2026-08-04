<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const appId = ref(''); const factor = ref('')
const load = useAction(() => api(`/api/agreement/${appId.value}`))
const sign = useAction(() => api(`/api/agreement/${appId.value}/sign`,
  { method: 'PUT', body: { identityFactor: factor.value.trim() } }))
</script>

<template>
  <h1>劳务协议</h1>
  <p class="sub">协议签署是「完成履约」的前置门禁</p>

  <div class="card">
    <h3>查看与签署</h3>
    <p class="hint">电子签通道是 mock：会返回成功，但没有真实的第三方存证</p>
    <div class="row">
      <div class="field"><label>报名单 ID</label><input v-model="appId" /></div>
      <div class="field"><label>身份要素（可空）</label><input v-model="factor" placeholder="签署校验用" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!appId" @click="load.run()">查看</button></div>
      <div class="field" style="flex:none"><button :disabled="!appId" @click="sign.run()">签署</button></div>
    </div>
    <Result :loading="load.loading.value" :error="load.error.value" :data="load.data.value" />
    <Result :loading="sign.loading.value" :error="sign.error.value" :data="sign.done.value ? '' : null" ok-text="协议已签署" />
  </div>
</template>
