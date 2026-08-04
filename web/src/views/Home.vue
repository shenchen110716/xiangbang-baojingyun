<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, auth } from '../api'

const health = ref<any>(null)
const unread = ref<number | null>(null)
const credit = ref<any>(null)

onMounted(async () => {
  try { health.value = await api('/actuator/health') } catch { /* 健康检查拿不到不影响别的 */ }
  try { unread.value = (await api<any>('/api/notification/unread-count')).count } catch {}
  try { credit.value = await api('/api/review/credit') } catch {}
})

const dsUp = () => {
  const c = health.value?.components?.db?.components || {}
  const all = Object.values(c) as any[]
  return `${all.filter(x => x.status === 'UP').length} / ${all.length}`
}
</script>

<template>
  <h1>概览</h1>
  <p class="sub">当前登录：用户 #{{ auth.userId }}（{{ auth.phone }}）</p>

  <div class="grid">
    <div class="card">
      <h3>系统健康</h3>
      <p class="hint">来自 /actuator/health</p>
      <dl class="kv">
        <dt>整体</dt><dd><span class="tag" :class="health?.status === 'UP' ? 'ok' : 'bad'">{{ health?.status ?? '…' }}</span></dd>
        <dt>数据源</dt><dd>{{ health ? dsUp() : '…' }}</dd>
        <dt>Outbox 卡死</dt><dd>{{ health?.components?.outbox?.details?.['卡死事件数'] ?? '…' }}</dd>
      </dl>
    </div>

    <div class="card">
      <h3>我的</h3>
      <p class="hint">信用分来自评价域，未接单时可能还没有</p>
      <dl class="kv">
        <dt>未读消息</dt><dd>{{ unread ?? '—' }}</dd>
        <dt>信用分</dt><dd>{{ credit?.score ?? '—' }}</dd>
      </dl>
    </div>
  </div>

  <div class="card">
    <h3>这是测试环境</h3>
    <p class="hint" style="margin:0">
      代发、电子签、推送三个外部通道都是 <strong>mock</strong>：会显示「工资已发放」、扣减监管账户、
      写入完税凭证号，<strong>而钱一分没动</strong>。用来验流程可以，不能当真实业务用。
    </p>
  </div>
</template>
