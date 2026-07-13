<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as positionsApi from '@/api/positions'
import type { ActualEmployer } from '@/api/types'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'

const loading = ref(true)
const list = ref<ActualEmployer[]>([])
const search = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await positionsApi.listActualEmployers()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  if (!search.value) return list.value
  const q = search.value.toLowerCase()
  return list.value.filter((x) => [x.name, x.credit_code, x.contact, x.phone].some((v) => v.toLowerCase().includes(q)))
})
const activeCount = computed(() => list.value.filter((x) => x.status === 'active').length)
const pausedCount = computed(() => list.value.filter((x) => x.status === 'paused').length)

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ name: '', credit_code: '', contact: '', phone: '' })
function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', credit_code: '', contact: '', phone: '' })
  formVisible.value = true
}
function openEdit(item: ActualEmployer) {
  editingId.value = item.id
  Object.assign(form, { name: item.name, credit_code: item.credit_code, contact: item.contact, phone: item.phone })
  formVisible.value = true
}
async function submitForm() {
  if (!form.name) { ElMessage.error('请填写单位名称'); return }
  try {
    if (editingId.value) await positionsApi.updateActualEmployer(editingId.value, form)
    else await positionsApi.createActualEmployer(form)
    ElMessage.success('保存成功')
    formVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function toggleStatus(item: ActualEmployer) {
  const next = item.status === 'active' ? 'paused' : 'active'
  try {
    await positionsApi.setActualEmployerStatus(item.id, next)
    ElMessage.success(next === 'active' ? '已恢复使用' : '已暂停使用')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeItem(item: ActualEmployer) {
  try {
    await ElMessageBox.confirm(`确定删除工作单位「${item.name}」吗？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await positionsApi.deleteActualEmployer(item.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="work-units-panel">
    <div class="stat-grid">
      <StatTile label="工作单位总数" :value="list.length" />
      <StatTile label="正常使用" :value="activeCount" hint-type="success" />
      <StatTile label="已暂停" :value="pausedCount" hint-type="warning" />
    </div>

    <PageCard title="工作单位列表" :count="filtered.length">
      <template #actions>
        <el-button type="primary" @click="openCreate">＋ 新增实际工作单位</el-button>
      </template>
      <div class="filter-row"><FilterBar v-model:search="search" /></div>
      <el-table :data="filtered" size="small">
        <el-table-column prop="name" label="单位名称" min-width="160" />
        <el-table-column prop="credit_code" label="统一社会信用代码" min-width="160" />
        <el-table-column prop="contact" label="联系人" width="110" />
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'danger'" size="small">
              {{ row.status === 'active' ? '正常' : '已暂停' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="toggleStatus(row)">{{ row.status === 'active' ? '暂停' : '恢复' }}</el-button>
            <el-button link type="danger" size="small" @click="removeItem(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑工作单位' : '新增实际工作单位'" width="480px">
      <el-form :model="form" label-width="140px">
        <el-form-item label="单位名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="统一社会信用代码"><el-input v-model="form.credit_code" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.work-units-panel {
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
</style>
