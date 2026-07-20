<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { routes } from '@/router/routes'
import * as miscApi from '@/api/misc'
import { listLinkedAccounts, type LinkedAccount } from '@/api/auth'
import GlobalSearch from '@/components/GlobalSearch.vue'
import HelpDrawer from '@/components/HelpDrawer.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const messageCount = ref(0)
const searchVisible = ref(false)
const helpVisible = ref(false)
const linkedAccounts = ref<LinkedAccount[]>([])
const switcherVisible = ref(false)
const switcherSearch = ref('')
const switching = ref(false)
const contentRef = ref<HTMLElement | null>(null)

watch(() => route.fullPath, () => {
  contentRef.value?.scrollTo(0, 0)
})

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
const projectManagerRouteNames = new Set([
  'home', 'screen', 'dispatch', 'workers', 'workRelations', 'claims', 'exports', 'settings',
])
const navGroups = computed(() => {
  const groups: Record<string, typeof navRoutes> = {}
  for (const r of navRoutes) {
    if (r.meta.adminOnly && auth.isEnterprise()) continue
    if (auth.isProjectManager() && !projectManagerRouteNames.has(String(r.name))) continue
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
        <div class="topbar-title">
          <h1>{{ currentTitle }}</h1>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="top-actions">
          <el-button v-if="!auth.isProjectManager()" link :icon="'Search'" @click="searchVisible = true" />
          <el-badge v-if="!auth.isProjectManager()" :value="messageCount" :hidden="messageCount === 0">
            <el-button link :icon="'Bell'" @click="router.push({ name: 'message' })" />
          </el-badge>
          <el-button link :icon="'QuestionFilled'" title="系统帮助" @click="helpVisible = true" />
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
      <div class="content" ref="contentRef">
        <router-view />
      </div>
    </main>
    <GlobalSearch v-model="searchVisible" />
    <HelpDrawer v-model="helpVisible" />

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
  height: 100vh;
  overflow: hidden;
  background: var(--el-bg-color-page);
}
.sidebar {
  width: 240px;
  background: #0f172a;
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
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.logo {
  background: var(--app-brand-gradient);
  color: #fff;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  font-weight: 700;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.4);
}
.brand-title {
  font-size: 15px;
}
.account-card {
  margin: 14px;
  padding: 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
  display: flex;
  align-items: center;
  gap: 9px;
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: rgba(59, 130, 246, 0.25);
  color: #93c5fd;
  display: grid;
  place-items: center;
  font-size: 12px;
}
.avatar.small {
  width: 22px;
  height: 22px;
  font-size: 10px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.account-info {
  display: grid;
  font-size: 12px;
  color: #e2e8f0;
}
.account-info small {
  color: #94a3b8;
}
.account-card.clickable {
  cursor: pointer;
}
.account-card.clickable:hover {
  background: rgba(255, 255, 255, 0.1);
}
.switch-hint {
  margin-left: auto;
  font-size: 11px;
  color: #93c5fd;
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
  background: transparent;
  padding: 6px 10px 20px;
}
.nav-group-label {
  padding: 16px 12px 6px;
  font-size: 11px;
  letter-spacing: 0.04em;
  color: #64748b;
  text-transform: uppercase;
}
.side-nav :deep(.el-menu-item) {
  height: 42px;
  line-height: 42px;
  margin: 2px 0;
  border-radius: 8px;
  color: #cbd5e1;
}
.side-nav :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}
.side-nav :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary);
  color: #fff;
  font-weight: 600;
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
  height: 68px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: saturate(1.4) blur(8px);
  border-bottom: 1px solid var(--el-border-color-lighter);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  position: sticky;
  top: 0;
  z-index: 9;
}
.topbar-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.topbar-title h1 {
  margin: 0;
  font-size: var(--app-fs-h1);
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--el-text-color-primary);
  line-height: 1.2;
}
.topbar-title :deep(.el-breadcrumb) {
  font-size: 11.5px;
  line-height: 1.2;
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
  flex: 1;
  overflow-y: auto;
  padding: 28px;
  max-width: 1500px;
  width: 100%;
  margin: 0 auto;
}
</style>
