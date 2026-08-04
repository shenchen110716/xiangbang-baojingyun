<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, when } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const limit = ref('20')
const list = useAction(() => api<any[]>(`/api/notification?limit=${encodeURIComponent(limit.value)}`))
const count = useAction(() => api('/api/notification/unread-count'))
const mark = useAction((id: number) => api(`/api/notification/${id}/read`, { method: 'PUT' }))

async function markAndReload(id: number) { await mark.run(id); await list.run(); await count.run() }
onMounted(() => { list.run(); count.run() })
</script>

<template>
  <h1>消息</h1>
  <p class="sub">全平台通知的统一出口：录用、签约、结算、发放都会到这里</p>

  <div class="card">
    <div class="row" style="margin-bottom:12px">
      <div class="field" style="flex:0 0 110px"><label>条数</label><input v-model="limit" /></div>
      <div class="field" style="flex:none"><button class="ghost" @click="list.run()">刷新</button></div>
      <div class="field" style="flex:none">
        <span class="tag" :class="(count.data.value as any)?.count ? 'warn' : 'ok'">
          未读 {{ (count.data.value as any)?.count ?? '…' }}
        </span>
      </div>
    </div>

    <div v-if="list.error.value" class="msg bad">{{ list.error.value }}</div>
    <div v-else-if="list.loading.value" class="msg info">加载中…</div>
    <div v-else-if="!list.data.value?.length" class="empty">还没有消息</div>
    <table v-else>
      <thead><tr><th style="width:90px">状态</th><th>内容</th><th style="width:150px">时间</th><th style="width:80px"></th></tr></thead>
      <tbody>
        <tr v-for="n in list.data.value" :key="n.id">
          <td><span class="tag" :class="n.read ? 'ok' : 'warn'">{{ n.read ? '已读' : '未读' }}</span></td>
          <td>{{ n.content || n.title || n.type }}</td>
          <td style="color:var(--muted)">{{ when(n.createdAt) }}</td>
          <td><button v-if="!n.read" class="ghost sm" @click="markAndReload(n.id)">标已读</button></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
