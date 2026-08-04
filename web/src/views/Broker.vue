<script setup lang="ts">
import { ref } from 'vue'
import { api, yuan } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const workerId = ref(''); const cid = ref('')
const reg = useAction(() => api('/api/broker/register', { method: 'POST' }))
const bind = useAction(() => api('/api/broker/bind', { body: { workerUserId: Number(workerId.value) } }))
const load = useAction(() => api(`/api/broker/commission/${cid.value}`))
const pay = useAction(() => api(`/api/broker/commission/${cid.value}/pay`, { method: 'PUT' }))
</script>

<template>
  <h1>经纪人</h1>
  <p class="sub">经纪人邀请工人入驻，工人产生结算时经纪人拿佣金</p>

  <div class="grid">
    <div class="card">
      <h3>成为经纪人</h3>
      <p class="hint">用当前登录账号注册</p>
      <button :disabled="reg.loading.value" @click="reg.run()">注册为经纪人</button>
      <div style="height:12px"></div>
      <Result :loading="reg.loading.value" :error="reg.error.value" :data="reg.data.value" ok-text="已注册" />
    </div>

    <div class="card">
      <h3>绑定工人</h3>
      <div class="row">
        <div class="field"><label>工人用户 ID</label><input v-model="workerId" /></div>
        <div class="field" style="flex:none"><button :disabled="!workerId" @click="bind.run()">绑定</button></div>
      </div>
      <Result :loading="bind.loading.value" :error="bind.error.value" :data="bind.done.value ? '' : null" ok-text="已绑定" />
    </div>
  </div>

  <div class="card">
    <h3>佣金</h3>
    <p class="hint">支付佣金需要「平台运维」角色</p>
    <div class="row">
      <div class="field"><label>佣金 ID</label><input v-model="cid" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!cid" @click="load.run()">查询</button></div>
      <div class="field" style="flex:none"><button :disabled="!cid" @click="pay.run()">支付</button></div>
    </div>
    <div v-if="load.data.value" class="row" style="margin-bottom:10px">
      <span class="tag">{{ yuan((load.data.value as any).amountCents) }}</span>
      <span class="tag">{{ (load.data.value as any).status }}</span>
    </div>
    <Result :loading="load.loading.value" :error="load.error.value" :data="load.data.value" />
    <Result :loading="pay.loading.value" :error="pay.error.value" :data="pay.done.value ? '' : null" ok-text="佣金已支付" />
  </div>
</template>
