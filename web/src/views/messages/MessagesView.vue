<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMessages } from '@/api/misc'
import type { MessageItem } from '@/api/types'
import { formatDateTime } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'

const router = useRouter()
const loading = ref(true)
const rows = ref<MessageItem[]>([])
const search = ref('')

async function load() {
  loading.value = true
  try {
    rows.value = await listMessages()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  if (!search.value) return rows.value
  const q = search.value.toLowerCase()
  return rows.value.filter((x) => [x.title, x.content].some((v) => (v || '').toLowerCase().includes(q)))
})
const todoCount = computed(() => rows.value.filter((x) => x.type !== 'success').length)

const typeText: Record<string, string> = { warning: '预警', todo: '待办', danger: '紧急', success: '正常' }
const typeTag: Record<string, string> = { warning: 'warning', todo: 'info', danger: 'danger', success: 'success' }

const PATH_ROUTE_MAP: Record<string, string> = {
  billing: 'billing',
  employees: 'workers',
  claims: 'claims',
  positions: 'dispatch',
  home: 'home',
}
function handleOpen(item: MessageItem) {
  const segment = item.path.split('/').filter(Boolean)[1] || 'home'
  router.push({ name: PATH_ROUTE_MAP[segment] || 'home' })
}
</script>

<template>
  <div v-loading="loading" class="messages-view">
    <div class="stat-grid">
      <StatTile label="全部消息" :value="rows.length" />
      <StatTile label="待办提醒" :value="todoCount" hint-type="warning" />
    </div>

    <PageCard title="消息中心" :count="filtered.length" hint="余额预警、员工审核、岗位定类、理赔补件和保单提醒">
      <template #actions>
        <el-button @click="load">刷新消息</el-button>
      </template>
      <div class="filter-row"><FilterBar v-model:search="search" /></div>
      <el-table :data="filtered" size="small">
        <el-table-column label="消息" min-width="240">
          <template #default="{ row }">
            <div><b>{{ row.title }}</b></div>
            <small class="muted">{{ row.content }}</small>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }"><el-tag size="small" :type="typeTag[row.type] as any">{{ typeText[row.type] || row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ row.created_at ? formatDateTime(row.created_at) : '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleOpen(row)">立即处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>
  </div>
</template>

<style scoped>
.messages-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.filter-row {
  padding: 0 20px 14px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
