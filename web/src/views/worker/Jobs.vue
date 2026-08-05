<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, yuan } from '../../api'

const jobs = ref<any[]>([])
const recos = ref<any[]>([])
const loading = ref(true)
const err = ref('')
const msg = ref('')
const busy = ref(0)

async function load() {
  loading.value = true; err.value = ''
  try { jobs.value = await api('/api/job/open?limit=50') }
  catch (e: any) { err.value = e.message }
  finally { loading.value = false }
  // 推荐拿不到不该影响岗位列表 —— 没设画像时它本来就是空的
  try { recos.value = await api('/api/matching/jobs?limit=10') } catch { recos.value = [] }
}

/**
 * 分享岗位。**这是自动升级为业务员的入口** ——
 * 没有它,后端那整条"分享 → 归因 → 成交 → 升级"永远不会被触发。
 *
 * <p>同一个人重复分享同一个岗位拿到同一个码,所以可以放心多点几次。
 */
const shareLink = ref('')
async function share(job: any) {
  msg.value = ''; err.value = ''; busy.value = job.id
  try {
    const r = await api<{ code: string }>('/api/broker/shares', {
      body: { targetType: 'JOB', targetId: job.id } })
    shareLink.value = `${location.origin}/#/jobs?ref=${r.code}`
    let copied = false
    try { await navigator.clipboard.writeText(shareLink.value); copied = true } catch { /* 剪贴板不可用就让人自己复制 */ }
    msg.value = `《${job.title}》的分享链接已生成${copied ? '并复制' : '，请手动复制下方链接'}`
  } catch (e: any) { err.value = e.message }
  finally { busy.value = 0 }
}

async function apply(job: any) {
  msg.value = ''; err.value = ''; busy.value = job.id
  try {
    await api(`/api/engagement/${job.id}/apply`, { method: 'POST' })
    msg.value = `已报名《${job.title}》,去「我的报名」看进度`
  } catch (e: any) { err.value = e.message }
  finally { busy.value = 0 }
}

/**
 * 带分享码进来时先记下归属。
 *
 * <p>**失败不打扰用户**:归因不成功最多是这一单没算给分享人,
 * 而弹一个错会让人以为岗位打不开。已归属别人的返回 attributed:false,
 * 那不是错误(归属唯一)。
 */
async function attributeIfReferred() {
  const q = location.hash.split('?')[1]
  const ref = q ? new URLSearchParams(q).get('ref') : null
  if (!ref) return
  try { await api('/api/broker/shares/attribute', { body: { code: ref } }) }
  catch { /* 静默:归因失败不该影响找活 */ }
}

onMounted(async () => { await attributeIfReferred(); await load() })
</script>

<template>
  <h1>找活</h1>
  <p class="sub">开放中的岗位。报名要求先完成实名认证</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div v-if="shareLink" class="card note">
    <h3>分享链接</h3>
    <input :value="shareLink" readonly @focus="($event.target as HTMLInputElement).select()" />
    <p class="hint" style="margin-bottom:0">
      发给别人。<b>对方经这个链接报名并成交后，你会自动升级为业务员</b>，
      之后他产生的业绩你有提成。
    </p>
  </div>

  <div v-if="recos.length" class="card" style="background:var(--primary-soft);border-color:rgba(34,211,238,.28)">
    <h3>可能适合你</h3>
    <p class="hint">根据你的标签与期望推荐；改「画像与信用」会影响这里</p>
    <table>
      <tbody>
        <tr v-for="r in recos" :key="r.jobId ?? r.id">
          <td>岗位 #{{ r.jobId ?? r.id }}</td>
          <td style="color:var(--muted)">匹配度 {{ r.score?.toFixed?.(2) ?? r.score ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:12px">
      <h3 style="margin:0">全部开放岗位</h3>
      <button class="ghost sm" @click="load">刷新</button>
    </div>

    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!jobs.length" class="empty">暂时没有开放中的岗位</div>
    <table v-else>
      <thead>
        <tr><th>岗位</th><th style="width:110px">日薪</th><th style="width:110px">余额名额</th><th style="width:92px"></th></tr>
      </thead>
      <tbody>
        <tr v-for="j in jobs" :key="j.id">
          <td>
            <div style="font-weight:550">{{ j.title }}</div>
            <div style="color:var(--muted);font-size:12.5px">#{{ j.id }} · {{ j.description }}</div>
          </td>
          <td>{{ yuan(j.wageCents) }}</td>
          <td>
            <span class="tag" :class="(j.headcount - j.filledCount) > 0 ? 'ok' : 'bad'">
              {{ j.headcount - j.filledCount }} / {{ j.headcount }}
            </span>
          </td>
          <td>
            <div class="row" style="gap:6px">
              <button class="sm" :disabled="busy === j.id || (j.headcount - j.filledCount) <= 0"
                      @click="apply(j)">{{ busy === j.id ? '…' : '报名' }}</button>
              <button class="ghost sm" :disabled="busy === j.id" @click="share(j)">分享</button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
