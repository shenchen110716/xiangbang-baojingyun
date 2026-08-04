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

onMounted(load)
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
</template>
