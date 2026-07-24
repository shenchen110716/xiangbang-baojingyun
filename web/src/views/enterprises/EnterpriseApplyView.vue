<script setup lang="ts">
import { reactive, ref } from 'vue'
import { applyEnterprise } from '@/api/enterprises'

const form = reactive({
  enterprise_name: '',
  credit_code: '',
  contact: '',
  phone: '',
  username: '',
  password: '',
})
const loading = ref(false)
const errorText = ref('')
const submitted = ref(false)

async function submit() {
  errorText.value = ''
  if (!form.enterprise_name || !form.contact || !form.phone || !form.username || !form.password) {
    errorText.value = '请填写单位名称、联系人、联系电话、登录账号和密码'
    return
  }
  loading.value = true
  try {
    await applyEnterprise(form)
    submitted.value = true
  } catch (e) {
    errorText.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="apply-screen">
    <a class="apply-brand" href="/xbbzp.html">
      <span class="brand-mark">响</span>
      <span class="brand-text">响帮帮<span class="brand-sub">XIANGBANGBANG · 无忧保</span></span>
    </a>

    <div class="apply-card" v-if="!submitted">
      <h1>企业免费入驻</h1>
      <p class="apply-lede">填写单位信息和登录账号，提交后由平台审核，通过后即可用下方账号登录企业后台。</p>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="单位名称" required>
          <el-input v-model="form.enterprise_name" placeholder="请输入单位全称" />
        </el-form-item>
        <el-form-item label="统一社会信用代码">
          <el-input v-model="form.credit_code" placeholder="选填" />
        </el-form-item>
        <el-form-item label="联系人" required>
          <el-input v-model="form.contact" />
        </el-form-item>
        <el-form-item label="联系电话" required>
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="登录账号" required>
          <el-input v-model="form.username" placeholder="审核通过后用此账号登录企业后台" />
        </el-form-item>
        <el-form-item label="登录密码" required>
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
      </el-form>

      <p v-if="errorText" class="apply-error">{{ errorText }}</p>
      <el-button type="primary" size="large" :loading="loading" @click="submit" style="width:100%">提交申请</el-button>
      <a class="apply-back" href="/xbbzp.html">&larr; 返回官网</a>
    </div>

    <div class="apply-card apply-success" v-else>
      <h1>提交成功</h1>
      <p class="apply-lede">请等待平台审核，通过后可用刚才填写的账号密码登录企业后台。</p>
      <router-link class="apply-back" :to="{ name: 'login', query: { portal: 'enterprise' } }">前往登录页 &rarr;</router-link>
    </div>
  </div>
</template>

<style scoped>
.apply-screen {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 48px 20px;
  background: var(--el-bg-color-page, #f5f6f8);
}
.apply-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: inherit;
  margin-bottom: 32px;
}
.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #1f2a44;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.brand-text { font-weight: 700; }
.brand-sub {
  display: block;
  font-size: 11px;
  font-weight: 400;
  opacity: 0.6;
  letter-spacing: 0.04em;
}
.apply-card {
  width: 100%;
  max-width: 440px;
  background: var(--el-bg-color, #fff);
  border-radius: 16px;
  padding: 36px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
}
.apply-card h1 { font-size: 22px; margin-bottom: 8px; }
.apply-lede { font-size: 13.5px; color: var(--el-text-color-secondary); margin-bottom: 24px; }
.apply-error { color: var(--el-color-danger); font-size: 13px; margin-bottom: 12px; }
.apply-back { display: block; text-align: center; margin-top: 16px; font-size: 13px; color: var(--el-text-color-secondary); text-decoration: none; }
.apply-success { text-align: center; }
</style>
