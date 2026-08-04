<script setup lang="ts">
import { computed } from 'vue'
import { zhField, zhStatus, zhOrgType, zhAccount, zhRole, zhFactor, statusTone, isMoneyField } from '../i18n'
import { yuan } from '../api'

/**
 * 统一的「加载中 / 出错 / 结果」显示。
 *
 * <p>此前结果是直接 `JSON.stringify` 出来的 —— 字段名和枚举值全是英文,
 * 等于把接口原始响应糊到用户脸上。现在按字段名翻中文、金额按分转元、状态查词典。
 * 翻不到的**原样显示**:宁可露一个英文,也不要显示空白让人猜。
 */
const props = defineProps<{ loading?: boolean; error?: string; data?: unknown; okText?: string }>()

type Row = { k: string; label: string; value: string; tone: '' | 'ok' | 'warn' | 'bad' }

function fmt(k: string, v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (isMoneyField(k)) return yuan(Number(v))
  if (k === 'status') return zhStatus(v)
  if (k === 'type') return zhOrgType(v)
  if (k === 'accountType') return zhAccount(v)
  if (k === 'role') return zhRole(v)
  if (k === 'identityFactor') return zhFactor(v)
  if (k === 'read') return v ? '已读' : '未读'
  if (Array.isArray(v)) {
    return v.length ? v.map(x => (typeof x === 'object' ? JSON.stringify(x) : String(x))).join('、') : '（空）'
  }
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

function rowsOf(o: Record<string, unknown>): Row[] {
  return Object.entries(o).map(([k, v]) => ({
    k, label: zhField(k), value: fmt(k, v),
    tone: k === 'status' ? statusTone(v) : '',
  }))
}

const kind = computed(() => {
  const d = props.data
  if (d === null || d === undefined || d === '') return 'none'
  if (Array.isArray(d)) return d.length ? 'list' : 'empty'
  if (typeof d === 'object') return 'object'
  return 'scalar'
})

const rows = computed(() => (kind.value === 'object' ? rowsOf(props.data as any) : []))
const listRows = computed(() =>
  kind.value === 'list'
    ? (props.data as any[]).map(x =>
        typeof x === 'object' && x !== null
          ? rowsOf(x)
          : [{ k: 'v', label: '值', value: String(x), tone: '' as const }])
    : [])
</script>

<template>
  <div v-if="loading" class="msg info">请求中…</div>
  <div v-else-if="error" class="msg bad">{{ error }}</div>
  <template v-else-if="data !== null && data !== undefined">
    <div v-if="okText" class="msg ok">{{ okText }}</div>

    <dl v-if="kind === 'object'" class="kv" style="margin:0">
      <template v-for="r in rows" :key="r.k">
        <dt>{{ r.label }}</dt>
        <dd>
          <span v-if="r.tone" class="tag" :class="r.tone">{{ r.value }}</span>
          <template v-else>{{ r.value }}</template>
        </dd>
      </template>
    </dl>

    <div v-else-if="kind === 'list'">
      <div v-for="(item, i) in listRows" :key="i" class="list-item">
        <dl class="kv" style="margin:0">
          <template v-for="r in item" :key="r.k">
            <dt>{{ r.label }}</dt>
            <dd>
              <span v-if="r.tone" class="tag" :class="r.tone">{{ r.value }}</span>
              <template v-else>{{ r.value }}</template>
            </dd>
          </template>
        </dl>
      </div>
    </div>

    <div v-else-if="kind === 'empty'" class="empty">没有数据</div>
    <div v-else-if="kind === 'scalar'" class="msg info">{{ data }}</div>
  </template>
</template>

<style>
.list-item { padding: 11px 0; border-bottom: 1px solid var(--border); }
.list-item:last-child { border-bottom: none; }
.list-item:first-child { padding-top: 2px; }
</style>
