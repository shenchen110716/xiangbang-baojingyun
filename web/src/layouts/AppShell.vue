<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { routes } from '@/router/routes'
import * as miscApi from '@/api/misc'
import { listLinkedAccounts, type LinkedAccount } from '@/api/auth'
import GlobalSearch from '@/components/GlobalSearch.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const messageCount = ref(0)
const searchVisible = ref(false)
const linkedAccounts = ref<LinkedAccount[]>([])
const switcherVisible = ref(false)
const switcherSearch = ref('')
const switching = ref(false)

async function loadLinkedAccounts() {
  if (!auth.isEnterprise() || !auth.user?.is_owner) { linkedAccounts.value = []; return }
  try {
    linkedAccounts.value = await listLinkedAccounts()
  } catch {
    linkedAccounts.value = []
  }
}

const filteredAccounts = computed(() => {
  if (!switcherSearch.value) return linkedAccounts.value
  const q = switcherSearch.value.toLowerCase()
  return linkedAccounts.value.filter((x) => x.enterprise_name.toLowerCase().includes(q))
})

function openSwitcher() {
  if (!linkedAccounts.value.length) return
  switcherSearch.value = ''
  switcherVisible.value = true
}

async function doSwitch(account: LinkedAccount) {
  switching.value = true
  try {
    await auth.switchAccount(account.id)
    switcherVisible.value = false
    ElMessage.success(`已切换到 ${account.enterprise_name}`)
    router.push({ name: 'home' })
    loadLinkedAccounts()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    switching.value = false
  }
}

const navRoutes = routes.filter((r) => r.meta.group !== undefined && r.name !== 'login')
const navGroups = computed(() => {
  const groups: Record<string, typeof navRoutes> = {}
  for (const r of navRoutes) {
    if (r.meta.adminOnly && auth.isEnterprise()) continue
    const key = r.meta.group || ''
    if (!groups[key]) groups[key] = []
    groups[key].push(r)
  }
  return groups
})

const teamLabel = computed(() => (auth.isEnterprise() ? '工作单位管理' : '投保单位管理'))
function navLabel(r: (typeof navRoutes)[number]) {
  if (r.name === 'team') return teamLabel.value
  return r.meta.title
}
const currentTitle = computed(() => (route.name === 'team' ? teamLabel.value : (route.meta.title as string)))

const brandSubtitle = computed(() => (auth.isEnterprise() ? '响帮帮保经云 · 参保单位' : '响帮帮保经云'))
const accountSubtitle = computed(() => (auth.isEnterprise() ? '参保单位账户' : '平台运营账户'))

async function loadMessageCount() {
  try {
    const rows = await miscApi.listMessages()
    messageCount.value = rows.filter((r) => r.id !== 'welcome').length
  } catch {
    messageCount.value = 0
  }
}

onMounted(async () => {
  if (!auth.user) {
    await auth.loadProfile().catch(() => {})
  }
  if (auth.user?.role === 'salesperson') {
    router.replace({ name: 'agent-portal' })
    return
  }
  loadMessageCount()
  loadLinkedAccounts()
})

function handleLogout() {
  ElMessageBox.confirm('确定要退出登录吗？', '退出登录', { type: 'warning' }).then(() => {
    auth.logout()
    router.push({ name: 'login' })
  }).catch(() => {})
}

async function openPasswordChange() {
  router.push({ name: 'settings' })
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="logo">响</span>
        <div>
          <div class="brand-title">{{ brandSubtitle }}</div>
        </div>
      </div>
      <div class="account-card" :class="{ clickable: linkedAccounts.length > 0 }" @click="openSwitcher">
        <div class="avatar">{{ auth.user?.name?.slice(0, 1) || '?' }}</div>
        <div class="account-info">
          <b>{{ auth.user?.name || '加载中' }}</b>
          <small>{{ accountSubtitle }}</small>
        </div>
        <span v-if="linkedAccounts.length > 0" class="switch-hint">切换 ⇄</span>
      </div>
      <el-menu :default-active="route.name as string" router class="side-nav">
        <template v-for="(items, group) in navGroups" :key="group">
          <div v-if="group" class="nav-group-label">{{ group }}</div>
          <el-menu-item v-for="r in items" :key="r.name as string" :index="r.name as string" :route="{ name: r.name }">
            <span>{{ navLabel(r) }}</span>
            <el-badge v-if="r.meta.badge && messageCount > 0" :value="messageCount" class="nav-badge" />
          </el-menu-item>
        </template>
      </el-menu>
    </aside>
    <main class="main">
      <header class="topbar">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="top-actions">
          <el-button link :icon="'Search'" @click="searchVisible = true" />
          <el-badge :value="messageCount" :hidden="messageCount === 0">
            <el-button link :icon="'Bell'" @click="router.push({ name: 'message' })" />
          </el-badge>
          <el-dropdown>
            <span class="top-user">
              <span class="avatar small">{{ auth.user?.name?.slice(0, 1) || '?' }}</span>
              {{ auth.user?.name }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="openPasswordChange">修改密码</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <div class="content">
        <router-view />
      </div>
    </main>
    <GlobalSearch v-model="searchVisible" />

    <el-dialog v-model="switcherVisible" title="切换登录账户" width="420px">
      <el-input v-model="switcherSearch" placeholder="搜索公司名" clearable style="margin-bottom: 14px" />
      <div class="switch-list">
        <div v-for="item in filteredAccounts" :key="item.id" class="switch-row" :class="{ disabled: switching }" @click="!switching && doSwitch(item)">
          <div class="switch-name">{{ item.enterprise_name }}</div>
          <small class="muted">负责人：{{ item.name }}</small>
        </div>
        <el-empty v-if="!filteredAccounts.length" description="没有匹配的单位" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  min-height: 100vh;
  background: var(--el-fill-color-light);
}
.sidebar {
  width: 240px;
  background: #fff;
  border-right: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
}
.brand {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  font-weight: 700;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.logo {
  background: linear-gradient(135deg, #6576f6, #465bea);
  color: #fff;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-weight: 700;
}
.brand-title {
  font-size: 15px;
}
.account-card {
  margin: 14px;
  padding: 10px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
  display: flex;
  align-items: center;
  gap: 9px;
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #e3e7ff;
  color: #5769e8;
  display: grid;
  place-items: center;
  font-size: 12px;
}
.avatar.small {
  width: 22px;
  height: 22px;
  font-size: 10px;
}
.account-info {
  display: grid;
  font-size: 12px;
}
.account-info small {
  color: var(--el-text-color-secondary);
}
.account-card.clickable {
  cursor: pointer;
}
.account-card.clickable:hover {
  background: var(--el-fill-color);
}
.switch-hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--el-color-primary);
}
.switch-list {
  display: grid;
  gap: 8px;
  max-height: 360px;
  overflow-y: auto;
}
.switch-row {
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  cursor: pointer;
}
.switch-row:hover {
  background: var(--el-fill-color);
}
.switch-row.disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.switch-name {
  font-size: 13px;
  font-weight: 600;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.side-nav {
  flex: 1;
  border-right: none;
  overflow: auto;
}
.nav-group-label {
  padding: 14px 20px 4px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.nav-badge {
  margin-left: 6px;
}
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.topbar {
  height: 64px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-lighter);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
}
.top-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.top-user {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  cursor: pointer;
}
.content {
  padding: 28px;
  max-width: 1500px;
  width: 100%;
  margin: 0 auto;
}
</style>
