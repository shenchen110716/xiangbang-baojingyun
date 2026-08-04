<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, logout, api } from './api'
import { role, setRole, ROLES, NAV, type RoleKey } from './roles'

const route = useRoute()
const router = useRouter()
const open = ref(false)
const unread = ref(0)

const isLogin = computed(() => route.path === '/login')
const items = computed(() => NAV[role.value])
const roleLabel = computed(() => ROLES.find(r => r.key === role.value)?.label ?? '')

async function loadUnread() {
  if (!auth.token) { unread.value = 0; return }
  try { unread.value = (await api<{ count: number }>('/api/notification/unread-count')).count ?? 0 }
  catch { /* 未读数拿不到不该打断任何操作 */ }
}

function switchRole(r: RoleKey) {
  setRole(r)
  router.push(NAV[r][0].to)
}

onMounted(loadUnread)
watch(() => route.path, () => { open.value = false; loadUnread() })
</script>

<template>
  <router-view v-if="isLogin" />

  <div v-else class="app">
    <aside class="side" :class="{ open }">
      <div class="brand"><span class="dot"></span>响帮帮</div>

      <div class="nav-group">当前身份</div>
      <div class="role-switch">
        <button v-for="r in ROLES" :key="r.key" class="role-btn"
                :class="{ on: role === r.key }" :title="r.hint"
                @click="switchRole(r.key)">{{ r.label }}</button>
      </div>

      <div class="nav-group">{{ roleLabel }}</div>
      <nav class="nav">
        <router-link v-for="i in items" :key="i.to" :to="i.to" :class="{ on: route.path === i.to }">
          <span>{{ i.label }}</span>
          <span v-if="i.badge && unread > 0" class="badge">{{ unread }}</span>
        </router-link>
      </nav>
    </aside>

    <div class="main">
      <div class="topbar">
        <button class="ghost sm side-toggle" @click="open = !open">☰</button>
        <div style="flex:1"></div>
        <span class="tag">#{{ auth.userId }} · {{ auth.phone }}</span>
        <button class="ghost sm" @click="logout(); router.push('/login')">退出</button>
      </div>
      <div class="content"><router-view /></div>
    </div>
  </div>
</template>

<style>
.role-switch { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; padding: 0 14px 6px; }
.role-btn {
  background: var(--surface-2); color: var(--text-2); border: 1px solid var(--border);
  font-size: 12.5px; padding: 6px 4px; font-weight: 500;
}
.role-btn:hover:not(.on) { background: var(--bg); }
.role-btn.on { background: var(--primary); color: #fff; border-color: var(--primary); }
</style>
