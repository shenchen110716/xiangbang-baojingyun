<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../../api'

const list = ref<any[]>([])
const loading = ref(true)
const err = ref(''); const msg = ref(''); const busy = ref('')
const TYPE: Record<string, string> = { ENTERPRISE: '企业', FACTORY: '工厂', SERVICE_STATION: '服务站' }

async function load() {
  loading.value = true; err.value = ''
  try { list.value = await api('/api/org/pending') }
  catch (e: any) { err.value = e.message; list.value = [] }
  finally { loading.value = false }
}
async function act(id: number, what: 'approve' | 'reject', label: string) {
  msg.value = ''; err.value = ''; busy.value = `${id}-${what}`
  try { await api(`/api/org/${id}/${what}`, { method: 'PUT' }); msg.value = `组织 #${id} ${label}`; await load() }
  catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}
onMounted(load)
</script>

<template>
  <h1>组织审核</h1>
  <p class="sub">这是平台自己的活儿——没有「归属」可查，只能靠 PLATFORM_OPS 角色</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}<div style="margin-top:6px;font-size:12.5px">
    没有平台角色时这里会被拒绝，这是正常的。</div></div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:12px">
      <h3 style="margin:0">待审核队列</h3>
      <button class="ghost sm" @click="load">刷新</button>
    </div>
    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!list.length && !err" class="msg ok">没有待审核的组织</div>
    <table v-else-if="list.length">
      <thead><tr><th style="width:70px">ID</th><th>名称</th><th style="width:90px">类型</th>
                 <th style="width:100px">法人</th><th style="width:150px"></th></tr></thead>
      <tbody>
        <tr v-for="o in list" :key="o.id">
          <td>#{{ o.id }}</td>
          <td>{{ o.name }}<div style="color:var(--muted);font-size:12.5px">{{ o.creditCode }}</div></td>
          <td>{{ TYPE[o.type] ?? o.type }}</td>
          <td>#{{ o.legalRepUserId }}</td>
          <td>
            <div class="row" style="gap:6px">
              <button class="sm" :disabled="busy === `${o.id}-approve`" @click="act(o.id, 'approve', '已通过')">通过</button>
              <button class="ghost sm" :disabled="busy === `${o.id}-reject`" @click="act(o.id, 'reject', '已驳回')">驳回</button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
