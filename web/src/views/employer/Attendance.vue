<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api'
import { zhStatus, statusTone, zhSource, hours } from '../../i18n'

/**
 * 考勤录入。
 *
 * <p>这页是工资的源头:确认过的工时才进工资单。所以两件事在界面上必须分得清 ——
 * **草稿还能改,已确认的不能**。混在一起的话,人会以为改了,其实没改,
 * 而工资单已经按旧数字出去了。
 */

const jobs = ref<any[]>([])
const jobId = ref('')
const workers = ref<any[]>([])       // 已录用的报名单
const rows = ref<Record<number, any[]>>({})   // applicationId → 考勤
const summary = ref<Record<number, any>>({})
const expanded = ref<number | null>(null)

const loading = ref(true)
const busy = ref('')
const err = ref(''); const msg = ref('')

// 录入表单
const day = ref(new Date().toISOString().slice(0, 10))
const minutes = ref('480')
const remark = ref('')
const reason = ref('')

const selectedJob = computed(() => jobs.value.find(j => String(j.id) === jobId.value))

async function load() {
  loading.value = true; err.value = ''
  try {
    jobs.value = await api('/api/job/mine')
    if (!jobId.value && jobs.value.length) jobId.value = String(jobs.value[0].id)
    await loadWorkers()
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

async function loadWorkers() {
  if (!jobId.value) { workers.value = []; return }
  err.value = ''
  try {
    const all = await api<any[]>(`/api/engagement/job/${jobId.value}/applicants`)
    // 只有已录用/已完成的人才有考勤可录 —— 还没录用就录工时是没有意义的
    workers.value = all.filter(a => a.status === 'ACCEPTED' || a.status === 'COMPLETED')
    expanded.value = null
  } catch (e: any) { err.value = e.message; workers.value = [] }
}

async function toggle(app: any) {
  if (expanded.value === app.id) { expanded.value = null; return }
  expanded.value = app.id
  await refresh(app.id)
}

async function refresh(appId: number) {
  err.value = ''
  try {
    rows.value[appId] = await api(`/api/attendance/application/${appId}`)
    summary.value[appId] = await api(`/api/attendance/application/${appId}/summary`)
  } catch (e: any) { err.value = e.message; rows.value[appId] = [] }
}

async function record(appId: number) {
  msg.value = ''; err.value = ''; busy.value = `add-${appId}`
  try {
    await api('/api/attendance', { body: {
      applicationId: appId, workDate: day.value,
      minutes: Math.round(Number(minutes.value)),
      source: 'MANUAL', remark: remark.value.trim() || null,
      reason: reason.value.trim(),
    } })
    msg.value = `${day.value} 的考勤已录入（草稿，确认后才计入工资）`
    remark.value = ''; reason.value = ''
    await refresh(appId)
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function confirm(appId: number, id: number) {
  msg.value = ''; err.value = ''; busy.value = `ok-${id}`
  try {
    await api(`/api/attendance/${id}/confirm`, { method: 'PUT' })
    msg.value = `考勤 #${id} 已确认，将计入工资`
    await refresh(appId)
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function reopen(appId: number, id: number) {
  const why = prompt('撤回确认后，已出的工资单需要重算。请说明原因：')
  if (!why?.trim()) return
  msg.value = ''; err.value = ''; busy.value = `re-${id}`
  try {
    await api(`/api/attendance/${id}/reopen`, { method: 'PUT', body: { reason: why.trim() } })
    msg.value = `考勤 #${id} 已撤回确认`
    await refresh(appId)
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

const changes = ref<Record<number, any[]>>({})
async function showChanges(id: number) {
  if (changes.value[id]) { delete changes.value[id]; return }
  try { changes.value[id] = await api(`/api/attendance/${id}/changes`) }
  catch (e: any) { err.value = e.message }
}

onMounted(load)
</script>

<template>
  <h1>考勤录入</h1>
  <p class="sub">工时是工资的依据。<b>只有「已确认」的工时会计入工资单</b>，草稿不算</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <div class="row" style="justify-content:space-between;align-items:flex-end">
      <div class="field" style="flex:1">
        <label>选择岗位</label>
        <select v-model="jobId" @change="loadWorkers">
          <option v-for="j in jobs" :key="j.id" :value="String(j.id)">{{ j.title }}（#{{ j.id }}）</option>
        </select>
      </div>
      <button class="ghost sm" @click="load">刷新</button>
    </div>
  </div>

  <div v-if="loading" class="msg info">加载中…</div>
  <div v-else-if="!jobs.length" class="card note">
    <h3>还没有发布过岗位</h3>
    <p class="hint" style="margin:0">先去「我的岗位」发布岗位并录用工人，才有考勤可录。</p>
  </div>
  <div v-else-if="!workers.length" class="card note">
    <h3>这个岗位还没有已录用的工人</h3>
    <p class="hint" style="margin:0">
      在「我的岗位」里录用应聘者之后，人会出现在这里。<b>还没录用的人不能录考勤</b>。
    </p>
  </div>

  <div v-else class="card">
    <h3>{{ selectedJob?.title }} · 在岗工人</h3>
    <table>
      <thead>
        <tr><th style="width:110px">报名单</th><th style="width:120px">工人</th>
            <th style="width:110px">状态</th><th style="width:170px">已确认工时</th><th style="width:100px"></th></tr>
      </thead>
      <tbody>
        <template v-for="w in workers" :key="w.id">
          <tr>
            <td>#{{ w.id }}</td>
            <td>用户 #{{ w.applicantUserId }}</td>
            <td><span class="tag" :class="statusTone(w.status)">{{ zhStatus(w.status) }}</span></td>
            <td>
              <span v-if="summary[w.id]">
                {{ hours(summary[w.id].minutes) }} · {{ summary[w.id].workDays }} 天
              </span>
              <span v-else style="color:var(--muted)">展开查看</span>
            </td>
            <td><button class="ghost sm" @click="toggle(w)">{{ expanded === w.id ? '收起' : '考勤' }}</button></td>
          </tr>

          <tr v-if="expanded === w.id">
            <td colspan="5" style="background:var(--surface-2);padding:12px">
              <div class="row" style="align-items:flex-end;gap:8px;margin-bottom:12px">
                <div class="field" style="flex:0 0 150px"><label>日期</label>
                  <input type="date" v-model="day" /></div>
                <div class="field" style="flex:0 0 110px"><label>工时（分钟）</label>
                  <input v-model="minutes" /></div>
                <div class="field" style="flex:1"><label>备注</label>
                  <input v-model="remark" placeholder="选填，如：加班 2 小时" /></div>
                <div class="field" style="flex:1"><label>录入原因<span style="color:var(--bad)">*</span></label>
                  <input v-model="reason" placeholder="必填，订正时查得到是谁改的" /></div>
                <button :disabled="!reason.trim() || busy === `add-${w.id}`" @click="record(w.id)">录入</button>
              </div>

              <div v-if="!rows[w.id]?.length" class="empty" style="padding:8px 0">这个人还没有考勤记录</div>
              <table v-else>
                <thead><tr><th style="width:110px">日期</th><th style="width:120px">工时</th>
                           <th style="width:100px">来源</th><th style="width:90px">状态</th>
                           <th>备注</th><th style="width:170px"></th></tr></thead>
                <tbody>
                  <template v-for="r in rows[w.id]" :key="r.id">
                    <tr>
                      <td>{{ r.workDate }}</td>
                      <td>{{ hours(r.minutes) }}</td>
                      <td>{{ zhSource(r.source) }}</td>
                      <td><span class="tag" :class="statusTone(r.status)">{{ zhStatus(r.status) }}</span></td>
                      <td style="color:var(--muted)">{{ r.remark || '—' }}</td>
                      <td>
                        <div class="row" style="gap:6px">
                          <button v-if="r.status === 'DRAFT'" class="sm"
                                  :disabled="busy === `ok-${r.id}`" @click="confirm(w.id, r.id)">确认</button>
                          <button v-else class="ghost sm"
                                  :disabled="busy === `re-${r.id}`" @click="reopen(w.id, r.id)">撤回确认</button>
                          <button class="ghost sm" @click="showChanges(r.id)">订正记录</button>
                        </div>
                      </td>
                    </tr>
                    <tr v-if="changes[r.id]">
                      <td colspan="6" style="background:var(--surface);font-size:12.5px">
                        <div v-if="!changes[r.id].length" style="color:var(--muted)">没有订正过</div>
                        <div v-for="c in changes[r.id]" :key="c.id" style="padding:2px 0">
                          {{ c.changedAt?.slice(0, 16).replace('T', ' ') }} ·
                          用户 #{{ c.changedBy }} 把 <b>{{ c.oldValue ?? '空' }}</b> 改成
                          <b>{{ c.newValue }}</b> —— {{ c.reason }}
                        </div>
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>
</template>
