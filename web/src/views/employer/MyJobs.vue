<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, yuan } from '../../api'
import { zhStatus, statusTone } from '../../i18n'

const orgs = ref<any[]>([])
const jobs = ref<any[]>([])
const loading = ref(true)
const err = ref(''); const msg = ref('')

const orgId = ref(''); const title = ref(''); const desc = ref(''); const wage = ref('200')
/** 这个岗位的工作地点。留空则显示单位注册地址。 */
const workAddress = ref(''); const headcount = ref('1')

/** 展开中的岗位 → 它的应聘者。按岗位缓存,免得每次折叠再展开都重拉。 */
const expanded = ref<number | null>(null)

/**
 * 岗位画像:技能标签 + 坐标。**撮合就靠它。**
 *
 * 2026-08-07 审计发现:这个端点一直没有界面,所以从来没人填过 ——
 * 撮合器拿到的岗位没有标签也没有坐标,于是静默退化成只按薪资和信用排序,
 * 而界面上完全看不出来。
 */
const skillTags = ref<string[]>([])
const profileOf = ref<Record<number, any>>({})
const draftMust = ref<Record<number, string[]>>({})
const draftLat = ref<Record<number, string>>({})
const draftLon = ref<Record<number, string>>({})

async function loadSkillTags() {
  try { skillTags.value = (await api<any[]>('/api/ops/dict/SKILL_TAG')).map(i => i.value) }
  catch (e: any) { err.value = e.message }
}

async function loadJobProfile(jobId: number) {
  try {
    const p = await api<any>(`/api/profile/jobs/${jobId}`)
    profileOf.value[jobId] = p
    draftMust.value[jobId] = p.mustTags || []
    draftLat.value[jobId] = String(p.lat ?? '')
    draftLon.value[jobId] = String(p.lon ?? '')
  } catch {
    // 404 = 还没填过。**不是错误** —— 大部分岗位一开始都没有画像
    profileOf.value[jobId] = null
    draftMust.value[jobId] = draftMust.value[jobId] || []
  }
}

function toggleTag(jobId: number, tag: string) {
  const cur = draftMust.value[jobId] || []
  draftMust.value[jobId] = cur.includes(tag) ? cur.filter(t => t !== tag) : [...cur, tag]
}

async function saveJobProfile(jobId: number) {
  msg.value = ''; err.value = ''; busy.value = `prof-${jobId}`
  try {
    await api(`/api/profile/jobs/${jobId}`, { method: 'PUT', body: {
      mustTags: draftMust.value[jobId] || [],
      niceTags: [],
      lat: Number(draftLat.value[jobId] || 0),
      lon: Number(draftLon.value[jobId] || 0) } })
    msg.value = `岗位 #${jobId} 的画像已保存,撮合会用上它`
    await loadJobProfile(jobId)
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}
const applicants = ref<Record<number, any[]>>({})
const busy = ref('')

async function load() {
  loading.value = true; err.value = ''
  try {
    orgs.value = (await api<any[]>('/api/org/mine')).filter(o => o.status === 'APPROVED')
    if (!orgId.value && orgs.value.length) orgId.value = String(orgs.value[0].id)
    jobs.value = await api('/api/job/mine')
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

async function post() {
  msg.value = ''; err.value = ''
  try {
    const r = await api<{ id: number }>('/api/job', { body: {
      orgId: Number(orgId.value), title: title.value.trim(),
      description: desc.value.trim(), wageCents: Math.round(Number(wage.value) * 100),
      headcount: Math.max(1, Number(headcount.value) || 1),
      workAddress: workAddress.value.trim() || null } })
    msg.value = workAddress.value.trim()
      ? `岗位 #${r.id} 已发布,工作地点:${workAddress.value.trim()}`
      : `岗位 #${r.id} 已发布。**没填工作地点**,求职端会显示单位注册地址`
    title.value = ''; desc.value = ''; workAddress.value = ''; headcount.value = '1'
    await load()
  } catch (e: any) { err.value = e.message }
}

async function toggle(job: any) {
  if (expanded.value === job.id) { expanded.value = null; return }
  expanded.value = job.id
  loadJobProfile(job.id)
  err.value = ''
  try { applicants.value[job.id] = await api(`/api/engagement/job/${job.id}/applicants`) }
  catch (e: any) { err.value = e.message; applicants.value[job.id] = [] }
}

async function act(jobId: number, appId: number, what: 'accept' | 'reject' | 'complete', label: string) {
  msg.value = ''; err.value = ''; busy.value = `${appId}-${what}`
  try {
    await api(`/api/engagement/${appId}/${what}`, { method: 'PUT' })
    msg.value = `报名单 #${appId} ${label}`
    applicants.value[jobId] = await api(`/api/engagement/job/${jobId}/applicants`)
    jobs.value = await api('/api/job/mine')   // 录用会占名额,岗位那行的余额要跟着变
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

onMounted(() => { load(); loadSkillTags() })
</script>

<template>
  <h1>我的岗位</h1>
  <p class="sub">点开岗位看应聘者，直接录用；协议签署后才能确认履约完成</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div v-if="!loading && !orgs.length" class="card note">
    <h3>还没有已通过审核的组织</h3>
    <p class="hint" style="margin:0">
      发岗要求组织已通过平台审核。去「我的组织」提交入驻，等平台通过后再回来。
    </p>
  </div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:12px">
      <h3 style="margin:0">已发布的岗位</h3>
      <button class="ghost sm" @click="load">刷新</button>
    </div>
    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!jobs.length" class="empty">还没有发布过岗位</div>
    <table v-else>
      <thead>
        <tr><th>岗位</th><th style="width:100px">日薪</th><th style="width:100px">名额</th>
            <th style="width:90px">状态</th><th style="width:100px"></th></tr>
      </thead>
      <tbody>
        <template v-for="j in jobs" :key="j.id">
          <tr>
            <td>
              <div style="font-weight:550">{{ j.title }}</div>
              <div style="color:var(--muted);font-size:12.5px">#{{ j.id }} · 组织 #{{ j.orgId }}</div>
            </td>
            <td>{{ yuan(j.wageCents) }}</td>
            <td>{{ j.filledCount }} / {{ j.headcount }}</td>
            <td><span class="tag" :class="statusTone(j.status)">{{ zhStatus(j.status) }}</span></td>
            <td><button class="ghost sm" @click="toggle(j)">{{ expanded === j.id ? '收起' : '应聘者' }}</button></td>
          </tr>
          <tr v-if="expanded === j.id">
            <td colspan="5" style="background:var(--surface-2);padding:12px">
              <h4 style="margin:0 0 6px">岗位画像</h4>
              <p class="hint" style="margin-top:0">
                <b>撮合就靠它。</b>不填的话这个岗位在推荐里只按薪资和信用排序——
                技能和距离都用不上，而界面上完全看不出来。
                <span v-if="profileOf[j.id] === null" style="color:var(--bad)">这个岗位还没填过。</span>
              </p>
              <div style="margin-bottom:8px">
                <span v-for="t in skillTags" :key="t"
                      class="tag" style="cursor:pointer;margin-right:6px"
                      :class="(draftMust[j.id] || []).includes(t) ? 'ok' : ''"
                      @click="toggleTag(j.id, t)">{{ t }}</span>
              </div>
              <div class="row" style="align-items:flex-end;gap:8px">
                <div class="field" style="flex:0 0 150px"><label>纬度</label>
                  <input v-model="draftLat[j.id]" placeholder="如：31.2989" /></div>
                <div class="field" style="flex:0 0 150px"><label>经度</label>
                  <input v-model="draftLon[j.id]" placeholder="如：120.5853" /></div>
                <button class="sm" :disabled="busy === `prof-${j.id}`"
                        @click="saveJobProfile(j.id)">保存画像</button>
              </div>
              <p class="hint">
                技能标签从平台词表里选，<b>不能自己打字</b>——打出词表里没有的词，
                后端会拒，而那时你已经填完一整屏了。
              </p>

              <h4 style="margin:16px 0 6px">应聘者</h4>
              <div v-if="!applicants[j.id]?.length" class="empty" style="padding:8px 0">还没有人报名</div>
              <table v-else>
                <thead><tr><th style="width:100px">报名单</th><th style="width:110px">应聘者</th>
                           <th style="width:100px">状态</th><th></th></tr></thead>
                <tbody>
                  <tr v-for="a in applicants[j.id]" :key="a.id">
                    <td>#{{ a.id }}</td>
                    <td>用户 #{{ a.applicantUserId }}</td>
                    <td><span class="tag" :class="statusTone(a.status)">{{ zhStatus(a.status) }}</span></td>
                    <td>
                      <div class="row" style="gap:6px">
                        <button v-if="a.status === 'SUBMITTED'" class="sm"
                                :disabled="busy === `${a.id}-accept`" @click="act(j.id, a.id, 'accept', '已录用')">录用</button>
                        <button v-if="a.status === 'SUBMITTED'" class="ghost sm"
                                :disabled="busy === `${a.id}-reject`" @click="act(j.id, a.id, 'reject', '已拒绝')">拒绝</button>
                        <button v-if="a.status === 'ACCEPTED'" class="sm"
                                :disabled="busy === `${a.id}-complete`" @click="act(j.id, a.id, 'complete', '已确认完成')">确认履约完成</button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>

  <div class="card" v-if="orgs.length">
    <h3>发布新岗位</h3>
    <div class="row">
      <div class="field" style="flex:0 0 220px"><label>所属组织</label>
        <select v-model="orgId">
          <option v-for="o in orgs" :key="o.id" :value="String(o.id)">{{ o.name }}（#{{ o.id }}）</option>
        </select>
      </div>
      <div class="field"><label>岗位标题</label><input v-model="title" placeholder="如：装配工 · 白班" /></div>
      <div class="field" style="flex:0 0 130px"><label>日薪（元）</label><input v-model="wage" /></div>
      <div class="field" style="flex:0 0 110px"><label>名额</label><input v-model="headcount" /></div>
    </div>
    <div class="field"><label>工作地点（选填）</label>
      <input v-model="workAddress" placeholder="如：苏州市吴中区太湖大道 99 号 3 号工地" /></div>
    <p class="hint">
      <b>每个岗位可以在不同的地方。</b>同一家单位常在几个工地同时开工——
      留空的话求职端显示的是<b>单位注册地址</b>，工人可能跑错地方，
      而这种错只有到了现场才发现。
    </p>
    <div class="field"><label>岗位描述</label><textarea v-model="desc" placeholder="工作内容、要求"></textarea></div>
    <button :disabled="!orgId || !title || !desc" @click="post">发布</button>
  </div>
</template>
