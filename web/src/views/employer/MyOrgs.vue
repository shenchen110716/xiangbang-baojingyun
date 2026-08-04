<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../../api'

const orgs = ref<any[]>([])
const loading = ref(true)
const err = ref(''); const msg = ref('')
const type = ref('ENTERPRISE'); const name = ref(''); const code = ref('')

const TYPE: Record<string, string> = { ENTERPRISE: '企业', FACTORY: '工厂', SERVICE_STATION: '服务站' }
const STATUS: Record<string, { t: string; c: string }> = {
  PENDING: { t: '待审核', c: 'warn' }, APPROVED: { t: '已通过', c: 'ok' }, REJECTED: { t: '已驳回', c: 'bad' },
}

async function load() {
  loading.value = true; err.value = ''
  try { orgs.value = await api('/api/org/mine') }
  catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

async function submit() {
  msg.value = ''; err.value = ''
  try {
    const r = await api<{ id: number }>('/api/org', {
      body: { type: type.value, name: name.value.trim(), creditCode: code.value.trim() } })
    msg.value = `已提交，组织 #${r.id} 等待平台审核`
    name.value = ''; code.value = ''
    await load()
  } catch (e: any) { err.value = e.message }
}
onMounted(load)
</script>

<template>
  <h1>我的组织</h1>
  <p class="sub">提交入驻后要等平台审核通过，才能发布岗位</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:12px">
      <h3 style="margin:0">已提交的组织</h3>
      <button class="ghost sm" @click="load">刷新</button>
    </div>
    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!orgs.length" class="empty">还没有组织，在下面提交入驻</div>
    <table v-else>
      <thead><tr><th style="width:70px">ID</th><th>名称</th><th style="width:90px">类型</th><th style="width:100px">状态</th></tr></thead>
      <tbody>
        <tr v-for="o in orgs" :key="o.id">
          <td>#{{ o.id }}</td>
          <td>{{ o.name }}<div style="color:var(--muted);font-size:12.5px">{{ o.creditCode }}</div></td>
          <td>{{ TYPE[o.type] ?? o.type }}</td>
          <td><span class="tag" :class="STATUS[o.status]?.c">{{ STATUS[o.status]?.t ?? o.status }}</span></td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="card">
    <h3>提交入驻</h3>
    <p class="hint">提交人自动成为法人代表；需要先完成实名认证</p>
    <div class="row">
      <div class="field" style="flex:0 0 140px"><label>类型</label>
        <select v-model="type">
          <option value="ENTERPRISE">企业</option><option value="FACTORY">工厂</option>
          <option value="SERVICE_STATION">服务站</option>
        </select>
      </div>
      <div class="field"><label>名称</label><input v-model="name" placeholder="组织全称" /></div>
      <div class="field"><label>统一社会信用代码</label><input v-model="code" placeholder="18 位" /></div>
      <div class="field" style="flex:none"><button :disabled="!name || !code" @click="submit">提交</button></div>
    </div>
  </div>
</template>
