<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, logout, api } from './api'

const route = useRoute()
const router = useRouter()
const open = ref(false)
const unread = ref(0)

const isLogin = computed(() => route.path === '/login')

// 导航按业务链路分组,不按域的字母序 —— 使用者关心的是流程顺序,
// 而域清单是给写代码的人看的。
const groups = [
  { title: '主链路', items: [
    { to: '/', label: '概览' },
    { to: '/identity', label: '身份与实名' },
    { to: '/org', label: '组织入驻' },
    { to: '/job', label: '岗位' },
    { to: '/engagement', label: '报名与录用' },
    { to: '/agreement', label: '劳务协议' },
    { to: '/settlement', label: '结算' },
    { to: '/fund', label: '资金与代发' },
  ]},
  { title: '增长', items: [
    { to: '/matching', label: '智能推荐' },
    { to: '/profile', label: '画像与偏好' },
    { to: '/talent', label: '人才库' },
    { to: '/review', label: '评价与信用' },
    { to: '/broker', label: '经纪人' },
    { to: '/voice', label: '语音发单' },
  ]},
  { title: '支撑', items: [
    { to: '/notification', label: '消息', badge: true },
    { to: '/ops', label: '运维 · Outbox' },
  ]},
]

async function loadUnread() {
  if (!auth.token) { unread.value = 0; return }
  try { unread.value = (await api<{ count: number }>('/api/notification/unread-count')).count ?? 0 }
  catch { /* 未读数拿不到不该打断任何操作,静默即可 */ }
}

onMounted(loadUnread)
watch(() => route.path, () => { open.value = false; loadUnread() })

function doLogout() { logout(); router.push('/login') }
</script>

<template>
  <router-view v-if="isLogin" />

  <div v-else class="app">
    <aside class="side" :class="{ open }">
      <div class="brand"><span class="dot"></span>响帮帮</div>
      <template v-for="g in groups" :key="g.title">
        <div class="nav-group">{{ g.title }}</div>
        <nav class="nav">
          <router-link v-for="i in g.items" :key="i.to" :to="i.to"
                       :class="{ on: route.path === i.to }">
            <span>{{ i.label }}</span>
            <span v-if="i.badge && unread > 0" class="badge">{{ unread }}</span>
          </router-link>
        </nav>
      </template>
    </aside>

    <div class="main">
      <div class="topbar">
        <button class="ghost sm side-toggle" @click="open = !open">☰</button>
        <div style="flex:1"></div>
        <span class="tag">用户 #{{ auth.userId }} · {{ auth.phone }}</span>
        <button class="ghost sm" @click="doLogout">退出</button>
      </div>
      <div class="content"><router-view /></div>
    </div>
  </div>
</template>
