<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../../api'

const list = ref<any[]>([])
const loading = ref(true)
const err = ref(''); const msg = ref('')
const busy = ref(0)
const factor = ref('SMS')

const STATUS: Record<string, { text: string; cls: string }> = {
  SUBMITTED: { text: '待处理', cls: 'warn' },
  ACCEPTED:  { text: '已录用', cls: 'ok' },
  REJECTED:  { text: '已拒绝', cls: 'bad' },
  COMPLETED: { text: '已完成', cls: 'ok' },
}

async function load() {
  loading.value = true; err.value = ''
  try { list.value = await api('/api/engagement/mine') }
  catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

async function sign(a: any) {
  msg.value = ''; err.value = ''; busy.value = a.id
  try {
    await api(`/api/agreement/${a.id}/sign`, { method: 'PUT', body: { identityFactor: factor.value.trim() } })
    msg.value = `报名单 #${a.id} 的协议已签署`
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = 0 }
}

onMounted(load)
</script>

<template>
  <h1>我的报名</h1>
  <p class="sub">录用后要签劳务协议，签完用工方才能确认履约完成</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:12px">
      <h3 style="margin:0">全部报名</h3>
      <button class="ghost sm" @click="load">刷新</button>
    </div>
    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!list.length" class="empty">还没有报名记录，去「找活」看看</div>
    <table v-else>
      <thead><tr><th style="width:90px">报名单</th><th style="width:90px">岗位</th><th style="width:100px">状态</th><th></th></tr></thead>
      <tbody>
        <tr v-for="a in list" :key="a.id">
          <td>#{{ a.id }}</td>
          <td>#{{ a.jobId }}</td>
          <td><span class="tag" :class="STATUS[a.status]?.cls">{{ STATUS[a.status]?.text ?? a.status }}</span></td>
          <td>
            <button v-if="a.status === 'ACCEPTED'" class="sm" :disabled="busy === a.id" @click="sign(a)">
              {{ busy === a.id ? '…' : '签署协议' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    <div class="field" style="margin-top:14px;max-width:300px">
      <label>签署身份因子（必填）</label>
      <select v-model="factor">
        <option value="SMS">短信验证</option>
        <option value="FACE">人脸识别</option>
      </select>
      <div style="color:var(--muted);font-size:12.5px;margin-top:5px">
        协议必须带身份因子才能签署，这是法律效力要件，不是可选项。
      </div>
    </div>
  </div>
</template>
