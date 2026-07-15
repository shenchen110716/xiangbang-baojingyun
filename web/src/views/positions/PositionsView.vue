<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as positionsApi from '@/api/positions'
import { listPlans } from '@/api/plans'
import type { ActualEmployer, InsurancePlan, PositionVideo, WorkPosition } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import PageCard from '@/components/PageCard.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatTile from '@/components/StatTile.vue'
import DetailModal from '@/components/DetailModal.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'
import { formatDateTime } from '@/utils/format'

const auth = useAuthStore()
const isEnterprise = computed(() => auth.isEnterprise())

const loading = ref(true)
const list = ref<WorkPosition[]>([])
const employers = ref<ActualEmployer[]>([])
const plans = ref<InsurancePlan[]>([])
const search = ref('')

async function load() {
  loading.value = true
  try {
    const [positions, employerList, planList] = await Promise.all([
      positionsApi.listPositions(),
      positionsApi.listActualEmployers(),
      listPlans(),
    ])
    list.value = positions
    employers.value = employerList
    plans.value = planList
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  if (!search.value) return list.value
  const q = search.value.toLowerCase()
  return list.value.filter((x) => [x.name, x.actual_employer_name || x.actual_employer, x.occupation_class].some((v) => (v || '').toLowerCase().includes(q)))
})
const { page, pageSize, total: pagedTotal, paged } = usePagedList(filtered)
const pendingCount = computed(() => list.value.filter((x) => x.status === 'pending').length)
const approvedCount = computed(() => list.value.filter((x) => x.status === 'approved').length)

function statusLabel(item: WorkPosition) {
  if (item.status === 'approved') return { text: '已定类', type: 'success' }
  if (item.status === 'rejected') return { text: '审核驳回', type: 'danger' }
  if (item.status === 'supplement') return { text: '待补充材料', type: 'warning' }
  return { text: item.video_count ? '待平台定类' : '待上传视频', type: 'info' }
}

const activeEmployers = computed(() => employers.value.filter((x) => x.status === 'active'))

// ---- create/edit + video upload (enterprise) ----
const formVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ actual_employer_id: null as number | null, name: '' })
const videoFile = ref<File | null>(null)
const uploading = ref(false)

function openCreate() {
  editingId.value = null
  Object.assign(form, { actual_employer_id: null, name: '' })
  videoFile.value = null
  formVisible.value = true
}
function openEdit(item: WorkPosition) {
  editingId.value = item.id
  Object.assign(form, { actual_employer_id: item.actual_employer_id, name: item.name })
  videoFile.value = null
  formVisible.value = true
}
function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  videoFile.value = input.files?.[0] || null
}
async function submitForm() {
  if (!form.actual_employer_id || !form.name) { ElMessage.error('请选择实际工作单位并填写岗位名称'); return }
  if (!editingId.value && !videoFile.value) { ElMessage.error('新增岗位必须上传岗位视频'); return }
  const employer = employers.value.find((x) => x.id === form.actual_employer_id)
  uploading.value = true
  try {
    let id = editingId.value
    if (id) await positionsApi.updatePosition(id, { actual_employer_id: form.actual_employer_id, actual_employer: employer?.name || '', name: form.name })
    else {
      const created = await positionsApi.createPosition({ actual_employer_id: form.actual_employer_id, actual_employer: employer?.name || '', name: form.name })
      id = created.id
    }
    if (videoFile.value && id) await positionsApi.uploadPositionVideo(id, videoFile.value)
    ElMessage.success('保存成功')
    formVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    uploading.value = false
  }
}

async function removePosition(item: WorkPosition) {
  try {
    await ElMessageBox.confirm(`确定删除岗位「${item.name}」吗？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await positionsApi.deletePosition(item.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- video viewer ----
const videosVisible = ref(false)
const videosList = ref<PositionVideo[]>([])
const videosTargetId = ref<number | null>(null)
const deletingVideoId = ref<number | null>(null)
async function openVideos(item: WorkPosition) {
  videosVisible.value = true
  videosTargetId.value = item.id
  videosList.value = await positionsApi.listPositionVideos(item.id)
}
async function removeVideo(video: PositionVideo) {
  try {
    await ElMessageBox.confirm('删除后视频文件不可恢复，确认删除该无效视频？', '删除视频', { type: 'warning' })
  } catch {
    return
  }
  try {
    deletingVideoId.value = video.id
    await positionsApi.deletePositionVideo(video.id)
    ElMessage.success('已删除')
    if (videosTargetId.value) videosList.value = await positionsApi.listPositionVideos(videosTargetId.value)
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    deletingVideoId.value = null
  }
}

// ---- review (admin) ----
const reviewVisible = ref(false)
const reviewTarget = ref<WorkPosition | null>(null)
const reviewForm = reactive({ status: 'approved' as 'approved' | 'rejected' | 'supplement', occupation_class: '3类', plan_id: null as number | null, review_note: '' })
function openReview(item: WorkPosition) {
  reviewTarget.value = item
  Object.assign(reviewForm, { status: 'approved', occupation_class: item.occupation_class || '3类', plan_id: item.plan_id, review_note: '' })
  reviewVisible.value = true
}
async function submitReview() {
  if (!reviewTarget.value) return
  if (reviewForm.status === 'approved' && !reviewTarget.value.video_count) { ElMessage.error('岗位视频上传后才能审核通过'); return }
  if (reviewForm.status !== 'approved' && !reviewForm.review_note) { ElMessage.error('补件或驳回时必须填写审核意见'); return }
  try {
    await positionsApi.reviewPosition(reviewTarget.value.id, {
      status: reviewForm.status,
      occupation_class: reviewForm.status === 'approved' ? reviewForm.occupation_class : undefined,
      plan_id: reviewForm.status === 'approved' ? reviewForm.plan_id : null,
      review_note: reviewForm.review_note,
    })
    ElMessage.success('审核完成')
    reviewVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="positions-view">
    <div class="stat-grid">
      <StatTile label="岗位总数" :value="list.length" />
      <StatTile label="待定类" :value="pendingCount" hint-type="warning" />
      <StatTile label="已定类" :value="approvedCount" hint-type="success" />
    </div>

    <PageCard :title="isEnterprise ? '岗位管理' : '岗位审核与定类'" :count="filtered.length">
      <template #actions>
        <el-button v-if="isEnterprise" type="primary" @click="openCreate">＋ 新增岗位并上传视频</el-button>
      </template>
      <div class="filter-row"><FilterBar v-model:search="search" /></div>
      <el-table :data="paged" size="small" max-height="560">
        <el-table-column prop="name" label="岗位名称" min-width="140" />
        <el-table-column label="实际工作单位" min-width="150">
          <template #default="{ row }">{{ row.actual_employer_name || row.actual_employer }}</template>
        </el-table-column>
        <el-table-column label="岗位视频" width="110">
          <template #default="{ row }">
            <el-button v-if="row.video_count" link type="primary" size="small" @click="openVideos(row)">{{ isEnterprise ? '查看' : '管理视频' }}（{{ row.video_count }}）</el-button>
            <span v-else class="muted">未上传</span>
          </template>
        </el-table-column>
        <el-table-column prop="occupation_class" label="职业类别" width="100" />
        <el-table-column label="保险方案" min-width="140">
          <template #default="{ row }">{{ row.plan_name || '—' }}</template>
        </el-table-column>
        <el-table-column label="审核状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusLabel(row).type" size="small">{{ statusLabel(row).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核意见" min-width="160">
          <template #default="{ row }">{{ row.review_note || '—' }}</template>
        </el-table-column>
        <el-table-column label="添加人" width="100">
          <template #default="{ row }">{{ row.creator_name || '—' }}</template>
        </el-table-column>
        <el-table-column label="添加时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <template v-if="isEnterprise">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑/上传视频</el-button>
              <el-button link type="danger" size="small" @click="removePosition(row)">删除</el-button>
            </template>
            <el-button v-else link type="primary" size="small" @click="openReview(row)">审核定类</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑岗位' : '新增岗位'" width="500px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="实际工作单位" required>
          <el-select v-model="form.actual_employer_id" style="width: 100%" placeholder="请选择">
            <el-option v-for="e in activeEmployers" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="岗位名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="岗位视频" :required="!editingId">
          <input type="file" accept="video/mp4,video/quicktime,.m4v" @change="onFileChange" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <DetailModal v-model="videosVisible" title="岗位视频">
      <div v-for="v in videosList" :key="v.id" class="video-item">
        <video :src="v.url" controls style="width: 100%; max-height: 320px" />
        <div class="video-meta">
          <el-tag size="small" :type="v.status === 'approved' ? 'success' : v.status === 'rejected' ? 'danger' : 'info'">
            {{ v.status === 'approved' ? '已审核' : v.status === 'rejected' ? '已驳回' : '等待审核' }}
          </el-tag>
          <span v-if="v.review_note" class="muted">{{ v.review_note }}</span>
          <span class="muted">{{ formatDateTime(v.created_at) }}</span>
          <el-button v-if="!isEnterprise" link type="danger" size="small" :loading="deletingVideoId === v.id" @click="removeVideo(v)">删除无效视频</el-button>
        </div>
      </div>
      <el-empty v-if="!videosList.length" description="暂无视频" />
    </DetailModal>

    <el-dialog v-model="reviewVisible" title="岗位审核定类" width="480px">
      <el-form v-if="reviewTarget" :model="reviewForm" label-width="110px">
        <el-form-item label="岗位">
          <span>{{ reviewTarget.name }} · {{ reviewTarget.actual_employer_name || reviewTarget.actual_employer }}</span>
        </el-form-item>
        <p v-if="!reviewTarget.video_count" class="warning-text">该岗位尚未上传视频，无法审核通过。</p>
        <el-form-item label="审核结果">
          <el-select v-model="reviewForm.status" style="width: 100%">
            <el-option label="审核通过" value="approved" />
            <el-option label="待补充材料" value="supplement" />
            <el-option label="审核驳回" value="rejected" />
          </el-select>
        </el-form-item>
        <el-form-item label="职业类别">
          <el-select v-model="reviewForm.occupation_class" :disabled="reviewForm.status !== 'approved'" style="width: 100%">
            <el-option label="1-3类" value="1-3类" />
            <el-option label="4类" value="4类" />
            <el-option label="5类" value="5类" />
            <el-option label="超5类" value="超5类" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联保险方案">
          <el-select v-model="reviewForm.plan_id" :disabled="reviewForm.status !== 'approved'" clearable placeholder="暂不关联" style="width: 100%">
            <el-option v-for="p in plans" :key="p.id" :label="`${p.insurer} · ${p.name}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核意见" :required="reviewForm.status !== 'approved'">
          <el-input v-model="reviewForm.review_note" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" @click="submitReview">提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.positions-view {
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
  font-size: 12px;
}
.video-item {
  margin-bottom: 16px;
}
.video-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-top: 8px;
  font-size: 12px;
}
.warning-text {
  color: var(--el-color-danger);
  font-size: 12px;
  margin: 0 0 12px;
}
</style>
