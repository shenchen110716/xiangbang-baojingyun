<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { api, when } from '../../api'

type Setting = {
  key: string; value: string; valueType: string; category: string
  label: string; description: string | null; updatedAt: string | null; updatedBy: number | null
}

const list = ref<Setting[]>([])
const changes = ref<any[]>([])
const loading = ref(true)
const err = ref(''); const msg = ref('')

/** 正在编辑的那一项。一次只改一个 —— 批量改会让「理由」失去意义。 */
const editing = ref<Setting | null>(null)
const draft = ref('')
const reason = ref('')
const saving = ref(false)

const CATEGORY: Record<string, string> = {
  BROKER: '经纪人 / 业务员',
  COMMISSION: '佣金分成',
  CREDIT: '信用分',
  WAGE: '薪资合理性',
  DEPOSIT: '保证金',
  MATCHING: '匹配推荐',
  VOICE: '语音发单',
}

const grouped = computed(() => {
  const m = new Map<string, Setting[]>()
  for (const s of list.value) {
    if (!m.has(s.category)) m.set(s.category, [])
    m.get(s.category)!.push(s)
  }
  return [...m.entries()]
})

async function load() {
  loading.value = true; err.value = ''
  try {
    list.value = await api('/api/ops/settings')
    changes.value = await api('/api/ops/settings/changes')
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

function startEdit(s: Setting) {
  editing.value = s; draft.value = s.value; reason.value = ''; msg.value = ''; err.value = ''
}

async function save() {
  if (!editing.value) return
  saving.value = true; err.value = ''; msg.value = ''
  try {
    await api(`/api/ops/settings/${encodeURIComponent(editing.value.key)}`, {
      method: 'PUT', body: { value: draft.value.trim(), reason: reason.value.trim() } })
    msg.value = `「${editing.value.label}」已改为 ${draft.value.trim()}`
    editing.value = null
    await load()
  } catch (e: any) { err.value = e.message }
  finally { saving.value = false }
}


/**
 * 劳务协议模板。**只增不改** —— 已生效版本的正文永不修改,
 * 否则签过的协议就追溯不到当时的文本了(后端如此设计)。
 *
 * 2026-08-07 审计发现:这两个端点一直没有界面,
 * 也就是说**改劳务协议正文只能发一次版**。它是法律文本,迟早要改。
 */
const tpl = ref<any>(null)
const tplDraft = ref('')
const tplBusy = ref(false)

async function loadTemplate() {
  try {
    tpl.value = await api('/api/ops/templates/LABOR_AGREEMENT')
    tplDraft.value = tpl.value?.body || ''
  } catch (e: any) { err.value = e.message }
}

async function publishTemplate() {
  if (!tplDraft.value.trim()) { err.value = '正文不能为空'; return }
  if (tplDraft.value === tpl.value?.body) { err.value = '正文没有变化'; return }
  msg.value = ''; err.value = ''; tplBusy.value = true
  try {
    const r = await api<{ version: number }>('/api/ops/templates', { body: {
      templateKey: 'LABOR_AGREEMENT', body: tplDraft.value } })
    msg.value = `已发布第 ${r.version} 版。**此前签过的协议仍然指向它们当时的版本**`
    await loadTemplate()
  } catch (e: any) { err.value = e.message }
  finally { tplBusy.value = false }
}

onMounted(() => { load(); loadTemplate() })
</script>

<template>
  <h1>参数设置</h1>
  <p class="sub">这些值以前写死在代码里，改一个要重新发版。现在在这里改，立即生效</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card note">
    <h3>改动会被记录</h3>
    <p class="hint" style="margin:0">
      佣金比例这类参数直接决定分给谁多少钱，所以<strong>每次改动都要填理由</strong>，
      并连同操作人一起留痕。多实例部署时，别的实例最多 60 秒后看到新值。
    </p>
  </div>

  <div v-if="loading" class="msg info">加载中…</div>

  <div v-for="[cat, items] in grouped" :key="cat" class="card">
    <h3>{{ CATEGORY[cat] ?? cat }}</h3>
    <table>
      <thead>
        <tr><th>参数</th><th style="width:130px">当前值</th><th style="width:150px">最近改动</th><th style="width:80px"></th></tr>
      </thead>
      <tbody>
        <tr v-for="s in items" :key="s.key">
          <td>
            <div style="font-weight:550">{{ s.label }}</div>
            <div v-if="s.description" style="color:var(--muted);font-size:12.5px">{{ s.description }}</div>
            <div style="color:var(--muted);font-size:11.5px;font-family:var(--mono)">{{ s.key }}</div>
          </td>
          <td><span class="tag ok" style="font-family:var(--mono)">{{ s.value }}</span></td>
          <td style="color:var(--muted);font-size:12.5px">
            {{ s.updatedBy ? `${when(s.updatedAt)} · #${s.updatedBy}` : '未改过' }}
          </td>
          <td><button class="ghost sm" @click="startEdit(s)">修改</button></td>
        </tr>
      </tbody>
    </table>
  </div>

  <div v-if="editing" class="card" style="border-color:var(--primary)">
    <h3>修改「{{ editing.label }}」</h3>
    <p class="hint">类型 {{ editing.valueType }} · 当前值 {{ editing.value }}</p>
    <div class="row">
      <div class="field" style="flex:0 0 200px"><label>新值</label><input v-model="draft" /></div>
      <div class="field"><label>改动理由（必填）</label><input v-model="reason" placeholder="为什么要改，事后要能解释" /></div>
      <div class="field" style="flex:none">
        <button :disabled="saving || !draft.trim() || !reason.trim()" @click="save">保存</button>
      </div>
      <div class="field" style="flex:none"><button class="ghost" @click="editing = null">取消</button></div>
    </div>
  </div>

  <div class="card">
    <h3>改动记录</h3>
    <p class="hint">最近 50 条</p>
    <div v-if="!changes.length" class="empty">还没有任何改动</div>
    <table v-else>
      <thead><tr><th style="width:150px">时间</th><th>参数</th><th style="width:150px">改动</th>
                 <th style="width:80px">操作人</th><th>理由</th></tr></thead>
      <tbody>
        <tr v-for="c in changes" :key="c.id">
          <td style="color:var(--muted);font-size:12.5px">{{ when(c.changedAt) }}</td>
          <td style="font-family:var(--mono);font-size:12px">{{ c.key }}</td>
          <td style="font-family:var(--mono);font-size:12.5px">{{ c.oldValue }} → {{ c.newValue }}</td>
          <td>#{{ c.changedBy }}</td>
          <td>{{ c.reason }}</td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="card">
    <h3>劳务协议模板</h3>
    <p class="hint">
      工人签的就是这段文本（占位符由系统按单填充）。
      <b>发布是「只增不改」</b>——旧版本永远保留，此前签过的协议仍然指向它们当时的版本，
      否则事后追溯不到当时签的是什么。
      <span v-if="tpl">当前第 <b>{{ tpl.version }}</b> 版。</span>
    </p>
    <div class="field">
      <label>正文</label>
      <textarea v-model="tplDraft" rows="12"
                style="width:100%;font-family:ui-monospace,monospace;font-size:13px"></textarea>
    </div>
    <button :disabled="tplBusy || !tplDraft.trim() || tplDraft === tpl?.body"
            @click="publishTemplate">发布新版本</button>
    <p class="hint" style="margin-bottom:0">
      改动前请先看清占位符——删掉一个，往后所有协议里那个位置就是空的，
      而<b>签的时候没人会发现</b>。
    </p>
  </div>
</template>
