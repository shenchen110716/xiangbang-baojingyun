<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const type = ref('ENTERPRISE')
const name = ref('')
const creditCode = ref('')
const orgId = ref('')

const submit = useAction(() => api<{ id: number }>('/api/org',
  { body: { type: type.value, name: name.value.trim(), creditCode: creditCode.value.trim() } }))
const load = useAction(() => api(`/api/org/${orgId.value}`))
const approve = useAction(() => api(`/api/org/${orgId.value}/approve`, { method: 'PUT' }))
const reject = useAction(() => api(`/api/org/${orgId.value}/reject`, { method: 'PUT' }))
</script>

<template>
  <h1>组织入驻</h1>
  <p class="sub">提交主体资料 → 平台审核 → 通过后才能发岗</p>

  <div class="card">
    <h3>提交入驻</h3>
    <p class="hint">提交人自动成为该组织的法人代表</p>
    <div class="row">
      <div class="field" style="flex:0 0 150px"><label>主体类型</label>
        <select v-model="type">
          <option value="ENTERPRISE">企业</option>
          <option value="FACTORY">工厂</option>
          <option value="SERVICE_STATION">服务站</option>
        </select>
      </div>
      <div class="field"><label>名称</label><input v-model="name" placeholder="组织全称" /></div>
      <div class="field"><label>统一社会信用代码</label><input v-model="creditCode" placeholder="18 位" /></div>
      <div class="field" style="flex:none">
        <button :disabled="submit.loading.value || !name || !creditCode" @click="submit.run()">提交</button>
      </div>
    </div>
    <Result :loading="submit.loading.value" :error="submit.error.value" :data="submit.data.value"
            ok-text="已提交，记下返回的 id" />
  </div>

  <div class="card">
    <h3>查询与审核</h3>
    <p class="hint">审核动作要平台角色（PLATFORM_OPS），普通账号会被拒绝，这是正常的</p>
    <div class="row">
      <div class="field"><label>组织 ID</label><input v-model="orgId" placeholder="例如 1" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!orgId" @click="load.run()">查询</button></div>
      <div class="field" style="flex:none"><button :disabled="!orgId" @click="approve.run()">通过</button></div>
      <div class="field" style="flex:none"><button class="danger" :disabled="!orgId" @click="reject.run()">驳回</button></div>
    </div>
    <Result :loading="load.loading.value" :error="load.error.value" :data="load.data.value" />
    <Result :loading="approve.loading.value" :error="approve.error.value"
            :data="approve.done.value ? '' : null" ok-text="已通过审核" />
    <Result :loading="reject.loading.value" :error="reject.error.value"
            :data="reject.done.value ? '' : null" ok-text="已驳回" />
  </div>
</template>
