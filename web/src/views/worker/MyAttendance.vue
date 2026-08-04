<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api'
import { zhStatus, statusTone, zhSource, hours } from '../../i18n'

/**
 * 我的考勤。工人只看得到自己的 —— 后端按 token 里的用户查,不接受传 userId。
 *
 * <p>为什么工人要能看:工时是工资的依据,**只有工人自己知道那天到底干没干**。
 * 录错了没人核对,错的就直接变成工资单了。
 */

const rows = ref<any[]>([])
const loading = ref(true)
const err = ref('')

const to = ref(new Date().toISOString().slice(0, 10))
const from = ref(new Date(Date.now() - 30 * 86400_000).toISOString().slice(0, 10))

const confirmed = computed(() => rows.value.filter(r => r.status === 'CONFIRMED'))
const totalMinutes = computed(() => confirmed.value.reduce((s, r) => s + r.minutes, 0))
const draftCount = computed(() => rows.value.length - confirmed.value.length)

async function load() {
  loading.value = true; err.value = ''
  try { rows.value = await api(`/api/attendance/mine?from=${from.value}&to=${to.value}`) }
  catch (e: any) { err.value = e.message; rows.value = [] }
  finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <h1>我的考勤</h1>
  <p class="sub">工时是工资的依据。<b>对不上就找工厂订正，别等发工资那天</b></p>

  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <div class="row" style="align-items:flex-end;gap:8px">
      <div class="field" style="flex:0 0 160px"><label>起</label><input type="date" v-model="from" /></div>
      <div class="field" style="flex:0 0 160px"><label>止</label><input type="date" v-model="to" /></div>
      <button @click="load">查询</button>
    </div>
  </div>

  <div class="card" v-if="!loading && rows.length">
    <div class="row" style="gap:32px">
      <div>
        <div class="hint" style="margin:0">已确认工时</div>
        <div style="font-size:22px;font-weight:600">{{ hours(totalMinutes) }}</div>
      </div>
      <div>
        <div class="hint" style="margin:0">出勤天数</div>
        <div style="font-size:22px;font-weight:600">{{ confirmed.length }} 天</div>
      </div>
      <div v-if="draftCount">
        <div class="hint" style="margin:0">待确认</div>
        <div style="font-size:22px;font-weight:600;color:var(--warn,#eab308)">{{ draftCount }} 条</div>
      </div>
    </div>
    <p class="hint" style="margin-bottom:0">
      只有「已确认」的工时会计入工资。草稿状态的还可能被工厂订正。
    </p>
  </div>

  <div class="card">
    <h3>考勤明细</h3>
    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!rows.length" class="empty">这段时间没有考勤记录</div>
    <table v-else>
      <thead>
        <tr><th style="width:120px">日期</th><th style="width:130px">工时</th>
            <th style="width:110px">岗位</th><th style="width:110px">来源</th>
            <th style="width:100px">状态</th><th>备注</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in rows" :key="r.id">
          <td>{{ r.workDate }}</td>
          <td>{{ hours(r.minutes) }}</td>
          <td>#{{ r.jobId }}</td>
          <td>{{ zhSource(r.source) }}</td>
          <td><span class="tag" :class="statusTone(r.status)">{{ zhStatus(r.status) }}</span></td>
          <td style="color:var(--muted)">{{ r.remark || '—' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
