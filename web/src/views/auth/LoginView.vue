<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
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

const portals = [
  {
    key: 'admin' as const,
    eyebrow: '01 · 平台管理端',
    title: '总后台',
    desc: '岗位审核、保单与理赔管理、资金核算',
  },
  {
    key: 'enterprise' as const,
    eyebrow: '02 · 参保单位端',
    title: '企业 / HR 后台',
    desc: '批量参停保、员工报表、多单位切换',
  },
]

const activeCopy = computed(() => portals.find((p) => p.key === form.portal)!)

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
    <aside class="auth-side">
      <a class="side-brand" href="/xbbzp.html">
        <span class="brand-mark">响</span>
        <span class="brand-text">响帮帮<span class="brand-sub">XIANGBANGBANG · 保经云</span></span>
      </a>

      <div class="side-body">
        <div class="hero-eyebrow">AUTH · 响帮帮保经云</div>
        <h1>参保、理赔、结算，<br>三端实时同步。</h1>
        <p class="lede">{{ activeCopy.desc }}，登录后数据与移动端、总后台即时联动。</p>
      </div>

      <a class="back-link" href="/xbbzp.html">&larr; 返回官网</a>
    </aside>

    <main class="auth-main">
      <div class="auth-card">
        <div class="card-eyebrow">登录门户</div>
        <div class="portal-picker">
          <button
            v-for="p in portals"
            :key="p.key"
            type="button"
            class="portal-card"
            :class="{ active: form.portal === p.key }"
            @click="form.portal = p.key"
          >
            <span class="portal-eyebrow">{{ p.eyebrow }}</span>
            <span class="portal-title">{{ p.title }}</span>
          </button>
        </div>

        <form class="auth-form" @submit.prevent="submit">
          <label class="field">
            <span class="field-label">登录账号</span>
            <input v-model="form.username" class="field-input" placeholder="请输入账号" autocomplete="username" />
          </label>
          <label class="field">
            <span class="field-label">登录密码</span>
            <input
              v-model="form.password"
              type="password"
              class="field-input"
              placeholder="请输入密码"
              autocomplete="current-password"
            />
          </label>

          <p v-if="errorText" class="error-text">{{ errorText }}</p>

          <button type="submit" class="submit-btn" :disabled="loading">
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </form>
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth-screen {
  --ink: #0f1621;
  --navy: #1c2e52;
  --navy-2: #263a63;
  --navy-3: #324b7d;
  --amber: #e2960a;
  --amber-ink: #7a4c02;
  --paper: #eef0ec;
  --paper-2: #e4e7e0;
  --card: #ffffff;
  --steel: #5c6b7d;
  --steel-2: #8b96a3;
  --line: rgba(15, 22, 33, 0.12);
  --mono: ui-monospace, 'SF Mono', 'Cascadia Code', 'JetBrains Mono', Consolas, monospace;
  --cjk: 'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', -apple-system, BlinkMacSystemFont, sans-serif;

  min-height: 100vh;
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: var(--paper);
  color: var(--ink);
  font-family: var(--cjk);
}

@media (prefers-color-scheme: dark) {
  .auth-screen {
    --ink: #e9ecf1;
    --navy: #0e1830;
    --navy-2: #16223e;
    --navy-3: #22335a;
    --amber: #f0a628;
    --amber-ink: #2a1a02;
    --paper: #0b0f17;
    --paper-2: #121722;
    --card: #141b29;
    --steel: #9aa6b4;
    --steel-2: #6b7686;
    --line: rgba(233, 236, 241, 0.12);
  }
}

h1 {
  text-wrap: balance;
  font-weight: 800;
  letter-spacing: -0.01em;
  margin: 0;
}
p {
  margin: 0;
}

/* ---------- left panel ---------- */
.auth-side {
  position: relative;
  background: var(--navy);
  color: #fff;
  padding: 44px 52px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.auth-side::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(
    -35deg,
    color-mix(in srgb, var(--amber) 14%, transparent) 0 14px,
    transparent 14px 34px
  );
  opacity: 0.32;
  mask-image: linear-gradient(180deg, black, transparent 78%);
}

.side-brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 800;
  font-size: 17px;
  color: #fff;
}
.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.1);
  display: grid;
  place-items: center;
  color: var(--amber);
  font-family: var(--mono);
  font-weight: 700;
  font-size: 14px;
  flex: none;
}
.brand-sub {
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  color: rgba(255, 255, 255, 0.6);
  display: block;
  margin-top: 1px;
}

.side-body {
  position: relative;
  margin: auto 0;
}
.hero-eyebrow {
  font-family: var(--mono);
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--amber);
  margin-bottom: 18px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.hero-eyebrow::before {
  content: '';
  width: 22px;
  height: 2px;
  background: var(--amber);
  display: inline-block;
}
.side-body h1 {
  font-size: 36px;
  line-height: 1.2;
}
.side-body .lede {
  margin-top: 18px;
  font-size: 14.5px;
  color: rgba(255, 255, 255, 0.68);
  max-width: 38ch;
}

.back-link {
  position: relative;
  font-family: var(--mono);
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
  width: fit-content;
}
.back-link:hover {
  color: #fff;
}

/* ---------- right panel ---------- */
.auth-main {
  display: grid;
  place-items: center;
  padding: 40px;
}
.auth-card {
  width: 100%;
  max-width: 380px;
}
.card-eyebrow {
  font-family: var(--mono);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--steel);
  margin-bottom: 14px;
}

.portal-picker {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 28px;
}
.portal-card {
  text-align: left;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px 14px 16px;
  cursor: pointer;
  font-family: var(--cjk);
  color: var(--ink);
  transition: border-color 0.15s, box-shadow 0.15s;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.portal-card:hover {
  border-color: var(--steel-2);
}
.portal-card.active {
  border-color: var(--amber);
  box-shadow: 0 0 0 1px var(--amber);
}
.portal-eyebrow {
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  color: var(--steel);
}
.portal-title {
  font-size: 14.5px;
  font-weight: 700;
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 12.5px;
  color: var(--steel);
}
.field-input {
  font-family: var(--cjk);
  font-size: 14px;
  color: var(--ink);
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 11px 13px;
}
.field-input:focus-visible {
  outline: 2px solid var(--amber);
  outline-offset: 1px;
}

.error-text {
  font-size: 12.5px;
  color: #d64545;
}

.submit-btn {
  margin-top: 6px;
  padding: 13px;
  border-radius: 8px;
  border: none;
  background: var(--amber);
  color: var(--amber-ink);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}
.submit-btn:hover {
  filter: brightness(1.05);
}
.submit-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

@media (max-width: 860px) {
  .auth-screen {
    grid-template-columns: 1fr;
  }
  .auth-side {
    padding: 32px;
  }
  .side-body {
    margin: 32px 0;
  }
  .side-body h1 {
    font-size: 28px;
  }
}

@media (max-width: 640px) {
  .auth-side {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    padding: 16px 20px;
    min-height: 0;
  }
  .auth-side::before {
    display: none;
  }
  .side-body,
  .back-link {
    display: none;
  }
  .auth-main {
    padding: 28px 20px 40px;
    place-items: start stretch;
  }
  .auth-card {
    max-width: none;
  }
  .portal-picker {
    gap: 8px;
  }
}
</style>
