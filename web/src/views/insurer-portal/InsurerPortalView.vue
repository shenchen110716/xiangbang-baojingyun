<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { getInsurerProfile, submitInsurerProfileEdit } from '@/api/insurerPortal'
import type { Insurer } from '@/api/types'
import PageCard from '@/components/PageCard.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'

const router = useRouter()
const auth = useAuthStore()

const tab = ref('profile')
const loading = ref(true)
const passwordDialogVisible = ref(false)

const profile = ref<Insurer | null>(null)
const profileForm = reactive({ name: '', contact: '', phone: '' })
const profileSaving = ref(false)

async function loadProfile() {
  profile.value = await getInsurerProfile()
  if (profile.value) {
    Object.assign(profileForm, { name: profile.value.name, contact: profile.value.contact, phone: profile.value.phone })
  }
}

async function load() {
  loading.value = true
  try {
    if (!auth.user) await auth.loadProfile()
    if (auth.user?.role !== 'insurer') {
      router.replace({ name: 'home' })
      return
    }
    await loadProfile()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function submitProfileEdit() {
  if (!profileForm.name.trim()) { ElMessage.error('请填写保险公司名称'); return }
  profileSaving.value = true
  try {
    profile.value = await submitInsurerProfileEdit(profileForm)
    ElMessage.success('已提交，等待平台审核后生效')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    profileSaving.value = false
  }
}

function logout() {
  ElMessageBox.confirm('确定要退出登录吗？', '退出登录', { type: 'warning' }).then(() => {
    auth.logout()
    router.replace({ name: 'login' })
  })
}
</script>

<template>
  <div class="insurer-portal">
    <header class="portal-header">
      <div class="portal-brand">响帮帮无忧保 · 保司工作台</div>
      <div class="portal-actions">
        <span class="portal-user">{{ auth.user?.name }}</span>
        <el-button size="small" @click="passwordDialogVisible = true">修改密码</el-button>
        <el-button size="small" @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="portal-body" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane label="基本信息" name="profile">
          <PageCard title="保司基本信息" hint="修改需经平台审核通过后才会生效">
            <el-form :model="profileForm" label-width="120px" style="max-width: 480px; padding: 0 20px 20px">
              <el-form-item label="保险公司名称" required><el-input v-model="profileForm.name" /></el-form-item>
              <el-form-item label="联系人"><el-input v-model="profileForm.contact" /></el-form-item>
              <el-form-item label="联系电话"><el-input v-model="profileForm.phone" /></el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="profileSaving" @click="submitProfileEdit">提交变更</el-button>
              </el-form-item>
            </el-form>
            <div v-if="profile?.pending_submitted_at" class="pending-banner">
              有一项变更正在等待平台审核：{{ profile.pending_name || profile.name }} / {{ profile.pending_contact || profile.contact }} / {{ profile.pending_phone || profile.phone }}
            </div>
          </PageCard>
        </el-tab-pane>
      </el-tabs>
    </main>

    <PasswordChangeDialog v-model="passwordDialogVisible" />
  </div>
</template>

<style scoped>
.insurer-portal {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}
.portal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 28px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.portal-brand {
  font-weight: 700;
  font-size: 15px;
}
.portal-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.portal-user {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.portal-body {
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px 28px 40px;
}
.pending-banner {
  margin: 0 20px 20px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
}
</style>
