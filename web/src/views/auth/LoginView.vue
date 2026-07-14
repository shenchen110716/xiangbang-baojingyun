<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const isLocal = ['localhost', '127.0.0.1'].includes(window.location.hostname)

const form = reactive({
  portal: (route.query.portal === 'enterprise' ? 'enterprise' : 'admin') as 'admin' | 'enterprise',
  username: isLocal ? 'admin' : '',
  password: isLocal ? 'admin123' : '',
})
const loading = ref(false)
const errorText = ref('')

async function submit() {
  errorText.value = ''
  loading.value = true
  try {
    await auth.login(form.username, form.password, form.portal)
    const redirect = (route.query.redirect as string) || '/home'
    router.push(redirect)
  } catch (err) {
    errorText.value = err instanceof Error ? err.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-screen">
    <div class="auth-card">
      <h1>响帮帮保经云</h1>
      <p class="subtitle">保险经纪运营管理平台</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="登录门户">
          <el-radio-group v-model="form.portal">
            <el-radio-button value="admin">总后台 · 平台运营</el-radio-button>
            <el-radio-button value="enterprise">参保单位后台 · 企业 / HR</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="登录账号">
          <el-input v-model="form.username" placeholder="请输入账号" autocomplete="username" />
        </el-form-item>
        <el-form-item label="登录密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" autocomplete="current-password" @keyup.enter="submit" />
        </el-form-item>
        <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" style="margin-bottom: 16px" />
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">登录</el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.auth-screen {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: var(--el-fill-color-light);
}
.auth-card {
  width: 380px;
  background: #fff;
  border-radius: 12px;
  padding: 40px 36px;
  box-shadow: 0 25px 80px rgba(13, 21, 54, 0.12);
}
.auth-card h1 {
  margin: 0 0 4px;
  font-size: 22px;
}
.subtitle {
  margin: 0 0 24px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
