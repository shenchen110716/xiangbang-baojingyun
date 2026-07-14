<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listInsured } from '@/api/insured'
import type { InsuredPerson } from '@/api/types'
import { insuredStatusLabel } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import EmployeeDetailDialog from './EmployeeDetailDialog.vue'
import EmployeeEditorDialog from './EmployeeEditorDialog.vue'

const loading = ref(true)
const list = ref<InsuredPerson[]>([])
const search = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await listInsured()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  if (!search.value) return list.value
  const q = search.value.toLowerCase()
  return list.value.filter((x) => [x.name, x.id_number, x.enterprise_name, x.actual_employer_name, x.position_name].some((v) => (v || '').toLowerCase().includes(q)))
})

const detailVisible = ref(false)
const editorVisible = ref(false)
const activePerson = ref<InsuredPerson | null>(null)
function openDetail(item: InsuredPerson) {
  activePerson.value = item
  detailVisible.value = true
}
function openEditor(item: InsuredPerson | null) {
  activePerson.value = item
  editorVisible.value = true
}
function editFromDetail() {
  detailVisible.value = false
  editorVisible.value = true
}

function exportCsv() {
  const header = ['被保险人', '投保单位', '实际工作单位', '岗位', '职业类别', '保险方案', '状态']
  const rows = filtered.value.map((p) => [p.name, p.enterprise_name, p.actual_employer_name, p.position_name, p.occupation_class, p.plan_name, insuredStatusLabel(p).text])
  const csv = '﻿' + [header, ...rows].map((r) => r.map((v) => `"${(v || '').toString().replace(/"/g, '""')}"`).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `响帮帮保经云-劳动关系-${Date.now()}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}
</script>

<template>
  <div v-loading="loading" class="work-relations-view">
    <PageCard title="劳动关系与保障历史" :count="filtered.length" hint="查看每位员工在不同岗位/保单下的参保与保障历史">
      <template #actions>
        <el-button @click="exportCsv">导出关系</el-button>
        <el-button type="primary" @click="openEditor(null)">＋ 新增员工关系</el-button>
      </template>
      <div class="filter-row"><FilterBar v-model:search="search" /></div>
      <el-table :data="filtered" size="small" max-height="560">
        <el-table-column label="被保险人" min-width="130">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <small class="muted">{{ row.id_number }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="enterprise_name" label="投保单位" min-width="130" />
        <el-table-column prop="actual_employer_name" label="实际工作单位" min-width="130" />
        <el-table-column prop="position_name" label="岗位" min-width="110" />
        <el-table-column prop="occupation_class" label="职业类别" width="90" />
        <el-table-column prop="plan_name" label="保险方案" min-width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="insuredStatusLabel(row).type">{{ insuredStatusLabel(row).text }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看关系记录</el-button>
            <el-button link type="primary" size="small" @click="openEditor(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <EmployeeDetailDialog v-model="detailVisible" :person="activePerson" @edit="editFromDetail" @toggle-status="detailVisible = false" />
    <EmployeeEditorDialog v-model="editorVisible" :person="activePerson" @saved="load" />
  </div>
</template>

<style scoped>
.work-relations-view {
  display: grid;
  gap: 18px;
}
.filter-row {
  padding: 0 20px 14px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
