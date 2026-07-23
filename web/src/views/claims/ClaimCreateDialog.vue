<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listEnterprises } from '@/api/enterprises'
import { listInsured } from '@/api/insured'
import { createClaim } from '@/api/claims'
import type { Enterprise, InsuredPerson } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ created: [] }>()

const enterprises = ref<Enterprise[]>([])
const people = ref<InsuredPerson[]>([])
const saving = ref(false)

const form = reactive({
  enterprise_id: null as number | null,
  person_id: null as number | null,
  accident_at: '',
  accident_place: '',
  accident_type: '工伤事故',
  hospital: '',
  diagnosis: '',
  medical_cost: 0,
  amount: 0,
  contact_name: '',
  contact_phone: '',
  description: '',
})

watch(visible, async (isVisible) => {
  if (!isVisible) return
  Object.assign(form, { enterprise_id: auth.isEnterprise() ? auth.user?.enterprise_id ?? null : null, person_id: null, accident_at: '', accident_place: '', accident_type: '工伤事故', hospital: '', diagnosis: '', medical_cost: 0, amount: 0, contact_name: '', contact_phone: '', description: '' })
  const [enterpriseList, personList] = await Promise.all([listEnterprises(), listInsured()])
  enterprises.value = enterpriseList
  people.value = personList
})

const activePeople = computed(() => people.value.filter((p) => p.status === 'active' && (!form.enterprise_id || p.enterprise_id === form.enterprise_id)))

// 选中被保险人后自动带入联系人/联系电话（默认用本人信息，报案人可再改成其他联系人）
watch(
  () => form.person_id,
  (personId) => {
    const person = people.value.find((p) => p.id === personId)
    if (person) Object.assign(form, { contact_name: person.name, contact_phone: person.phone || '' })
  },
)

async function submit() {
  if (!form.enterprise_id || !form.person_id) { ElMessage.error('请选择投保单位和被保险人'); return }
  if (!form.accident_at || !form.accident_place || !form.description) { ElMessage.error('请填写事故时间、地点和案情描述'); return }
  saving.value = true
  try {
    await createClaim({ ...form, enterprise_id: form.enterprise_id, person_id: form.person_id })
    ElMessage.success('报案已提交')
    visible.value = false
    emit('created')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="新增工伤报案" width="560px" append-to-body destroy-on-close>
    <el-form :model="form" label-width="110px">
      <el-form-item label="投保单位" required>
        <el-select v-model="form.enterprise_id" :disabled="auth.isEnterprise()" style="width: 100%" placeholder="请选择">
          <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="被保险人" required>
        <el-select v-model="form.person_id" filterable style="width: 100%" placeholder="输入姓名搜索当前在保员工">
          <el-option v-for="p in activePeople" :key="p.id" :label="`${p.name} · ${p.id_number}`" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="事故时间" required>
        <el-date-picker v-model="form.accident_at" type="datetime" value-format="YYYY-MM-DD HH:mm" style="width: 100%" />
      </el-form-item>
      <el-form-item label="事故地点" required><el-input v-model="form.accident_place" /></el-form-item>
      <el-form-item label="事故类型"><el-input v-model="form.accident_type" /></el-form-item>
      <el-form-item label="就诊医院"><el-input v-model="form.hospital" /></el-form-item>
      <el-form-item label="诊断结果"><el-input v-model="form.diagnosis" /></el-form-item>
      <el-form-item label="医疗费用"><el-input-number v-model="form.medical_cost" :min="0" :step="100" /></el-form-item>
      <el-form-item label="预估理赔金额"><el-input-number v-model="form.amount" :min="0" :step="100" /></el-form-item>
      <el-form-item label="联系人"><el-input v-model="form.contact_name" /></el-form-item>
      <el-form-item label="联系电话"><el-input v-model="form.contact_phone" /></el-form-item>
      <el-form-item label="案情描述" required><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">提交报案</el-button>
    </template>
  </el-dialog>
</template>
