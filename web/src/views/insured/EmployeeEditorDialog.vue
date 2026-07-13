<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listEnterprises } from '@/api/enterprises'
import { listPositions } from '@/api/positions'
import { createInsured, updateInsured } from '@/api/insured'
import type { Enterprise, InsuredPerson, WorkPosition } from '@/api/types'

const props = defineProps<{ person: InsuredPerson | null }>()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ saved: [] }>()

const enterprises = ref<Enterprise[]>([])
const positions = ref<WorkPosition[]>([])
const form = reactive({
  enterprise_id: null as number | null,
  position_id: null as number | null,
  name: '',
  id_number: '',
  phone: '',
  effective_at: null as string | null,
  terminated_at: null as string | null,
})
const saving = ref(false)

watch(visible, async (isVisible) => {
  if (!isVisible) return
  const [enterpriseList, positionList] = await Promise.all([listEnterprises(), listPositions()])
  enterprises.value = enterpriseList
  positions.value = positionList
  if (props.person) {
    Object.assign(form, {
      enterprise_id: props.person.enterprise_id,
      position_id: props.person.position_id,
      name: props.person.name,
      id_number: props.person.id_number,
      phone: props.person.phone,
      effective_at: props.person.effective_at ? props.person.effective_at.slice(0, 10) : null,
      terminated_at: props.person.terminated_at ? props.person.terminated_at.slice(0, 10) : null,
    })
  } else {
    Object.assign(form, { enterprise_id: null, position_id: null, name: '', id_number: '', phone: '', effective_at: null, terminated_at: null })
  }
})

const availablePositions = computed(() =>
  positions.value.filter((p) => (p.status === 'approved' || p.id === props.person?.position_id) && (!form.enterprise_id || p.enterprise_id === form.enterprise_id)),
)
const selectedPositionHint = computed(() => {
  const pos = positions.value.find((p) => p.id === form.position_id)
  if (!pos) return '当前单位暂无审核通过的岗位'
  return `${pos.actual_employer_name || pos.actual_employer} · ${pos.name} · ${pos.occupation_class}`
})

async function submit() {
  if (!props.person && !form.enterprise_id) { ElMessage.error('请选择投保单位'); return }
  if (!form.position_id) { ElMessage.error('请选择已审核岗位'); return }
  if (!form.name || !form.id_number) { ElMessage.error('请填写姓名和身份证号'); return }
  saving.value = true
  try {
    if (props.person) {
      const payload: Partial<InsuredPerson> = { name: form.name, id_number: form.id_number, phone: form.phone }
      if (form.position_id !== props.person.position_id) payload.position_id = form.position_id
      if (form.effective_at !== (props.person.effective_at ? props.person.effective_at.slice(0, 10) : null)) payload.effective_at = form.effective_at
      if (form.terminated_at !== (props.person.terminated_at ? props.person.terminated_at.slice(0, 10) : null)) payload.terminated_at = form.terminated_at
      await updateInsured(props.person.id, payload)
    } else {
      await createInsured({
        enterprise_id: form.enterprise_id!, position_id: form.position_id, name: form.name, id_number: form.id_number, phone: form.phone,
        effective_at: form.effective_at, terminated_at: form.terminated_at,
      })
    }
    ElMessage.success('保存成功')
    visible.value = false
    emit('saved')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="person ? '编辑参保员工' : '新增参保员工'" width="520px" append-to-body destroy-on-close>
    <el-form :model="form" label-width="110px">
      <el-form-item label="投保单位" required>
        <el-select v-model="form.enterprise_id" :disabled="!!person" style="width: 100%" placeholder="请选择">
          <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="已审核岗位" required>
        <el-select v-model="form.position_id" style="width: 100%" placeholder="请选择">
          <el-option v-for="p in availablePositions" :key="p.id" :label="`${p.actual_employer_name || p.actual_employer} · ${p.name}`" :value="p.id" />
        </el-select>
        <small class="hint">{{ selectedPositionHint }}</small>
      </el-form-item>
      <el-form-item label="被保险人姓名" required><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="身份证号" required><el-input v-model="form.id_number" /></el-form-item>
      <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="参保时间">
        <el-date-picker v-model="form.effective_at" type="date" value-format="YYYY-MM-DD" placeholder="不填则不改变参保时间" style="width: 100%" />
        <small class="hint">留空则不修改；填写后员工将变为「在保」状态</small>
      </el-form-item>
      <el-form-item label="停保时间">
        <el-date-picker v-model="form.terminated_at" type="date" value-format="YYYY-MM-DD" placeholder="不填则不改变停保时间" style="width: 100%" />
        <small class="hint">留空则不修改；填写后员工将变为「已停保」状态</small>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  display: block;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  margin-top: 4px;
}
</style>
