<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, auth } from '../../api'
import { zhRole } from '../../i18n'

const mine = ref<string[]>([])
const targetId = ref(String(auth.userId))
const role = ref('PLATFORM_OPS')
const err = ref(''); const msg = ref(''); const busy = ref('')

async function loadMine() {
  err.value = ''
  try { mine.value = await api('/api/identity/roles') } catch (e: any) { err.value = e.message }
}
async function act(method: 'POST' | 'DELETE', label: string) {
  msg.value = ''; err.value = ''; busy.value = method
  try {
    await api('/api/identity/roles', { method, body: { targetUserId: Number(targetId.value), role: role.value } })
    msg.value = `用户 #${targetId.value} 的 ${role.value} ${label}`
    await loadMine()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}
onMounted(loadMine)
</script>

<template>
  <h1>角色管理</h1>
  <p class="sub">授权链的根。只有「平台管理员」能改角色</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <h3>我的角色</h3>
    <div class="row" style="gap:6px">
      <span v-if="!mine.length" class="tag">无平台角色</span>
      <span v-for="r in mine" :key="r" class="tag ok">{{ zhRole(r) }}</span>
      <button class="ghost sm" @click="loadMine">刷新</button>
    </div>
  </div>

  <div class="card">
    <h3>授予 / 收回</h3>
    <p class="hint">
      管理员和运维是分开的：<strong>管理员本身不能审核、不能放款</strong>，
      要动钱得先显式给自己授运维角色——而那会留下痕迹。
    </p>
    <div class="row">
      <div class="field" style="flex:0 0 160px"><label>目标用户 ID</label><input v-model="targetId" /></div>
      <div class="field" style="flex:0 0 200px"><label>角色</label>
        <select v-model="role">
          <option value="PLATFORM_OPS">平台运维</option>
          <option value="PLATFORM_ADMIN">平台管理员</option>
        </select>
      </div>
      <div class="field" style="flex:none"><button :disabled="!targetId || busy === 'POST'" @click="act('POST','已授予')">授予</button></div>
      <div class="field" style="flex:none"><button class="danger" :disabled="!targetId || busy === 'DELETE'" @click="act('DELETE','已收回')">收回</button></div>
    </div>
  </div>

  <div class="card" style="background:var(--surface-2)">
    <h3>第一个管理员从哪来</h3>
    <p class="hint" style="margin:0">
      授权链要有根：第一个「平台管理员」不可能由别的管理员授予。
      它由部署配置 <code>xbb.security.bootstrap-admin-phones</code> 指定，登录时自动授予。
      默认为空 —— <strong>谁都不是管理员，失败关闭</strong>。
    </p>
  </div>
</template>
