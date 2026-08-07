<script setup lang="ts">
/**
 * 字典维护（平台端）。
 *
 * <p>2026-08-07 审计发现：这 5 个端点一直没有界面入口，
 * 也就是说<b>加一个技能标签要发一次版</b>——而技能标签是撮合和人才库的词表，
 * 业务一扩就要动。
 *
 * <p><b>停用而不是删除。</b>已经有人打过这个标签、已经有评价用过它，
 * 删掉的话那些历史记录会指向一个不存在的词。
 */
import { ref, computed, onMounted } from 'vue'
import { api } from '../../api'

/** 词表类型。这几个是系统真的会去读的，别的填了也没人用。 */
const TYPES = [
  { v: 'SKILL_TAG', label: '技能标签', hint: '撮合的必备/加分技能、工人画像、人才库检索都用它' },
  { v: 'REVIEW_TAG_ORG_RATES_WORKER', label: '评价标签 · 企业评工人', hint: '需要 polarity 属性：POSITIVE / NEGATIVE' },
  { v: 'REVIEW_TAG_WORKER_RATES_ORG', label: '评价标签 · 工人评企业', hint: '同上' },
  { v: 'REVIEW_SEVERITY_WEIGHT', label: '评价严重度权重', hint: 'LIGHT / MEDIUM / HEAVY 的分值' },
]

const dictType = ref('SKILL_TAG')
const items = ref<any[]>([])
const loading = ref(false)
const busy = ref('')
const msg = ref(''); const err = ref('')

const newKey = ref(''); const newValue = ref(''); const newSort = ref('')
const newAttrs = ref('')

const current = computed(() => TYPES.find(t => t.v === dictType.value))
const usedKeys = computed(() => new Set(items.value.map(i => i.key)))

async function load() {
  loading.value = true; err.value = ''
  try { items.value = await api(`/api/ops/dict/${dictType.value}`) }
  catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

function parseAttrs(): Record<string, string> | null {
  const raw = newAttrs.value.trim()
  if (!raw) return {}
  try {
    const o = JSON.parse(raw)
    return typeof o === 'object' && o !== null ? o : null
  } catch { return null }
}

async function addItem() {
  const key = newKey.value.trim()
  if (!key) { err.value = '请填写编码'; return }
  if (usedKeys.value.has(key)) {
    // 同一个词表里编码重复的话，取数时拿到哪一条要看排序，而排序是隐式的
    err.value = `「${key}」已经在这个词表里了`; return
  }
  const attrs = parseAttrs()
  if (attrs === null) { err.value = '属性要是合法的 JSON 对象，如 {"polarity":"POSITIVE"}'; return }

  msg.value = ''; err.value = ''; busy.value = 'add'
  try {
    await api('/api/ops/dict', { body: {
      dictType: dictType.value,
      key,
      value: newValue.value.trim() || key,
      sortOrder: Number(newSort.value) || (items.value.length + 1),
      attributes: attrs } })
    msg.value = `已加入「${key}」`
    newKey.value = ''; newValue.value = ''; newSort.value = ''; newAttrs.value = ''
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function toggle(item: any) {
  msg.value = ''; err.value = ''; busy.value = `t-${item.id}`
  try {
    await api(`/api/ops/dict/${item.id}/enabled?enabled=${!item.enabled}`, { method: 'PUT' })
    msg.value = item.enabled ? `「${item.key}」已停用` : `「${item.key}」已启用`
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

const draftValue = ref<Record<number, string>>({})

async function rename(item: any) {
  const v = (draftValue.value[item.id] ?? '').trim()
  if (!v || v === item.value) { err.value = '显示名没有变化'; return }
  msg.value = ''; err.value = ''; busy.value = `v-${item.id}`
  try {
    await api(`/api/ops/dict/${item.id}/value`, { method: 'PUT', body: { value: v } })
    msg.value = `「${item.key}」的显示名已改为「${v}」`
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

onMounted(load)
</script>

<template>
  <h1>字典维护</h1>

  <div class="card note">
    <h3>改的是词表，不是数据</h3>
    <p class="hint" style="margin-bottom:0">
      技能标签、评价标签这些都存在这里。<b>此前没有界面，加一个标签要发一次版。</b>
      <br>
      <b>停用而不是删除</b>——已经有人打过这个标签、已经有评价用过它，
      删掉的话那些历史记录会指向一个不存在的词。停用之后新的地方选不到它，
      旧记录照常显示。
    </p>
  </div>

  <div v-if="err" class="error">{{ err }}</div>
  <div v-if="msg" class="ok">{{ msg }}</div>

  <div class="card">
    <div class="row">
      <div class="field" style="flex:0 0 320px"><label>词表</label>
        <select v-model="dictType" @change="load">
          <option v-for="t in TYPES" :key="t.v" :value="t.v">{{ t.label }}</option>
        </select>
      </div>
      <div class="field">
        <label>用在哪</label>
        <div class="hint" style="padding-top:8px">{{ current?.hint }}</div>
      </div>
    </div>
  </div>

  <div class="card">
    <h3>{{ current?.label }}</h3>
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!items.length" class="empty">这个词表还是空的</div>
    <table v-else>
      <thead><tr><th style="width:70px">序</th><th style="width:150px">编码</th>
                 <th style="width:220px">显示名</th><th style="width:200px">属性</th>
                 <th style="width:90px">状态</th><th style="width:180px"></th></tr></thead>
      <tbody>
        <tr v-for="i in items" :key="i.id" :style="i.enabled ? '' : 'opacity:.55'">
          <td>{{ i.sortOrder }}</td>
          <td><code>{{ i.key }}</code></td>
          <td>
            <input v-model="draftValue[i.id]" :placeholder="i.value" style="width:100%" />
          </td>
          <td class="hint" style="font-size:12px">
            {{ Object.keys(i.attributes || {}).length ? JSON.stringify(i.attributes) : '—' }}
          </td>
          <td>
            <span class="tag" :class="i.enabled ? 'ok' : ''">{{ i.enabled ? '启用' : '停用' }}</span>
          </td>
          <td>
            <div class="row" style="gap:6px">
              <button class="ghost sm" :disabled="busy === `v-${i.id}`" @click="rename(i)">改名</button>
              <button class="ghost sm" :disabled="busy === `t-${i.id}`" @click="toggle(i)">
                {{ i.enabled ? '停用' : '启用' }}
              </button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
    <p class="hint" style="margin-bottom:0">
      <b>「编码」是系统内部认的那个词，改不了。</b>
      已经存下来的画像、评价里记的都是它——改了的话那些记录就对不上了。
      要换叫法就改「显示名」。
    </p>
  </div>

  <div class="card">
    <h3>加一条</h3>
    <div class="row">
      <div class="field" style="flex:0 0 170px"><label>编码<span style="color:var(--bad)">*</span></label>
        <input v-model="newKey" placeholder="如：钳工" /></div>
      <div class="field" style="flex:0 0 200px"><label>显示名（不填=同编码）</label>
        <input v-model="newValue" /></div>
      <div class="field" style="flex:0 0 110px"><label>排序</label>
        <input v-model="newSort" placeholder="留空排最后" /></div>
      <div class="field"><label>属性（JSON，可留空）</label>
        <input v-model="newAttrs" placeholder='如：{"polarity":"POSITIVE"}' /></div>
      <button style="align-self:flex-end" :disabled="!newKey.trim() || busy === 'add'"
              @click="addItem">加入</button>
    </div>
    <p class="hint" style="margin-bottom:0">
      评价标签<b>必须带 <code>polarity</code></b>（POSITIVE / NEGATIVE）——
      不带的话评价算分时它既不加也不减，看着像生效了其实没有。
    </p>
  </div>
</template>
