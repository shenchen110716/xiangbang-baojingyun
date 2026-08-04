<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, auth, setAuth, setDevToken, fetchDevCode } from '../api'

const router = useRouter()
const phone = ref('13800000001')
const code = ref('')
const dev = ref(auth.devToken)
const err = ref('')
const note = ref('')
const busy = ref(false)

async function getCode() {
  err.value = ''; note.value = ''; busy.value = true
  try {
    setDevToken(dev.value.trim())
    code.value = await fetchDevCode(phone.value.trim())
    note.value = '验证码已取到并自动填入'
  } catch (e: any) {
    err.value = e.message
  } finally { busy.value = false }
}

async function login() {
  err.value = ''; busy.value = true
  try {
    const r = await api<{ userId: number; token: string; newUser: boolean }>(
      '/api/identity/login', { body: { phone: phone.value.trim(), code: code.value.trim() } })
    setAuth(r.token, r.userId, phone.value.trim())
    router.push('/')
  } catch (e: any) {
    err.value = e.message
  } finally { busy.value = false }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="brand"><span class="dot"></span>响帮帮</div>
      <p class="tagline">灵活用工平台 · 测试环境</p>

      <div class="card">
        <div v-if="err" class="msg bad">{{ err }}</div>
        <div v-else-if="note" class="msg ok">{{ note }}</div>

        <div class="field">
          <label>手机号</label>
          <input v-model="phone" placeholder="11 位手机号" @keyup.enter="getCode" />
        </div>

        <div class="field">
          <label>开发者口令</label>
          <input v-model="dev" type="password" placeholder="用于取验证码" />
        </div>

        <div class="field">
          <label>验证码</label>
          <div class="row">
            <input v-model="code" placeholder="6 位" style="flex:1" @keyup.enter="login" />
            <button class="ghost" :disabled="busy || !phone || !dev" @click="getCode">获取</button>
          </div>
        </div>

        <button style="width:100%" :disabled="busy || !phone || !code" @click="login">
          {{ busy ? '处理中…' : '登录 / 注册' }}
        </button>
      </div>

      <div class="card" style="background:var(--surface-2)">
        <h3>为什么要填口令</h3>
        <p class="hint" style="margin:0">
          没有接真实短信通道。而公开的取码接口<strong>按设计不回显验证码</strong>——
          它是免登录可达的，回显等于任何人报个手机号就能登进任意账号，包括平台管理员。
          所以取码走另一道要口令的门，口令只存在你的浏览器里。
        </p>
      </div>
    </div>
  </div>
</template>
