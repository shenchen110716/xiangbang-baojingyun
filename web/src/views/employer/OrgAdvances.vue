<script setup lang="ts">
/**
 * 机构端「借支管理」。
 *
 * <p>老板 2026-08-06:借支属于机构端。
 * <b>批了之后只能从这家单位的结算里扣回来</b> —— 不记归属的话,
 * 甲公司批的借支会从乙公司给同一个工人的付款里扣走。
 */
import { ref, computed, onMounted } from 'vue'
import { api, when, yuan } from '../../api'
import { zhStatus, statusTone } from '../../i18n'

const orgs = ref<any[]>([])
const orgId = ref('')
const advances = ref<any[]>([])
const loading = ref(false)
const busy = ref('')
const msg = ref(''); const err = ref('')

const worker = ref(''); const amountYuan = ref(''); const reason = ref('')

const currentOrg = computed(() => orgs.value.find(o => String(o.id) === orgId.value))
const outstanding = computed(() =>
  advances.value.filter(a => a.status === 'ACTIVE')
    .reduce((sum, a) => sum + (a.outstandingCents || 0), 0))

async function loadOrgs() {
  loading.value = true; err.value = ''
  try {
    orgs.value = (await api<any[]>('/api/org/mine')).filter(o => o.status === 'APPROVED')
    if (!orgId.value && orgs.value.length) orgId.value = String(orgs.value[0].id)
    if (orgId.value) await load()
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

async function load() {
  if (!orgId.value) return
  err.value = ''
  try { advances.value = await api(`/api/fund/advances/org/${orgId.value}`) }
  catch (e: any) { err.value = e.message }
}

async function grant() {
  const cents = Math.round(Number(amountYuan.value) * 100)
  if (!worker.value || !cents || cents <= 0) { err.value = '请填写工人编号和正的金额'; return }
  if (!reason.value.trim()) { err.value = '请填写借支事由'; return }
  msg.value = ''; err.value = ''; busy.value = 'grant'
  try {
    await api('/api/fund/advances', { body: {
      orgId: Number(orgId.value),
      workerUserId: Number(worker.value),
      amountCents: cents,
      reason: reason.value.trim() } })
    msg.value = `已批 ${yuan(cents)} 给工人 #${worker.value}`
    worker.value = ''; amountYuan.value = ''; reason.value = ''
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

onMounted(loadOrgs)
</script>

<template>
  <h1>借支管理</h1>

  <div class="card note">
    <h3>批了之后从你这家的工资里扣</h3>
    <p class="hint" style="margin-bottom:0">
      借支<b>记在批它的那家单位名下</b>，还款只从这家的结算里扣。
      不这样的话，你批的借支会从别家给同一个工人的付款里扣走——
      <b>你的钱没出，别人的工人少拿了</b>，而两边都不会报错。
    </p>
  </div>

  <div v-if="err" class="error">{{ err }}</div>
  <div v-if="msg" class="ok">{{ msg }}</div>
  <div v-if="loading" class="empty">加载中…</div>

  <div v-else-if="!orgs.length" class="card">
    <div class="empty">你名下还没有已审核的单位。</div>
  </div>

  <template v-else>
    <div class="card">
      <div class="row">
        <div class="field" style="flex:0 0 280px"><label>单位</label>
          <select v-model="orgId" @change="load">
            <option v-for="o in orgs" :key="o.id" :value="String(o.id)">
              {{ o.name }}（#{{ o.id }}）
            </option>
          </select>
        </div>
        <div class="field">
          <label>本单位未收回</label>
          <div style="font-size:24px;font-weight:600;padding-top:6px">{{ yuan(outstanding) }}</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>批一笔借支</h3>
      <div class="row">
        <div class="field" style="flex:0 0 160px"><label>工人编号</label>
          <input v-model="worker" placeholder="用户 id" /></div>
        <div class="field" style="flex:0 0 150px"><label>金额（元）</label>
          <input v-model="amountYuan" /></div>
        <div class="field"><label>事由<span style="color:var(--bad)">*</span></label>
          <input v-model="reason" placeholder="必填，事后对账要说得清为什么垫" /></div>
        <button style="align-self:flex-end"
                :disabled="!worker || !amountYuan || !reason.trim() || busy === 'grant'"
                @click="grant">批准</button>
      </div>
      <p class="hint" style="margin-bottom:0">
        有额度上限（参数中心配），<b>连同这个工人已欠的一起算</b>——
        只看单笔的话，借十次小额就绕过去了。
      </p>
    </div>

    <div class="card">
      <h3>{{ currentOrg?.name }} 批过的借支</h3>
      <div v-if="!advances.length" class="empty">还没有借支记录</div>
      <table v-else>
        <thead><tr><th style="width:80px">编号</th><th style="width:110px">工人</th>
                   <th style="width:110px">金额</th><th style="width:110px">未还</th>
                   <th style="width:100px">状态</th><th>事由</th>
                   <th style="width:150px">时间</th></tr></thead>
        <tbody>
          <tr v-for="a in advances" :key="a.id">
            <td>#{{ a.id }}</td>
            <td>用户 #{{ a.workerUserId }}</td>
            <td>{{ yuan(a.amountCents) }}</td>
            <td>{{ yuan(a.outstandingCents) }}</td>
            <td><span class="tag" :class="statusTone(a.status)">{{ zhStatus(a.status) }}</span></td>
            <td>{{ a.reason }}</td>
            <td>{{ when(a.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </template>
</template>
