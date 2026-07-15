<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword } from '@/api/auth'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = ref(props.modelValue)
watch(
  () => props.modelValue,
  (v) => {
    visible.value = v
  },
)
watch(visible, (v) => emit('update:modelValue', v))

const form = reactive({ current_password: '', new_password: '' })
const submitting = ref(false)

function reset() {
  form.current_password = ''
  form.new_password = ''
}

async function submit() {
  if (!form.current_password) {
    ElMessage.error('请输入当前密码')
    return
  }
  if (form.new_password.length < 6) {
    ElMessage.error('新密码至少 6 位')
    return
  }
  submitting.value = true
  try {
    await changePassword(form.current_password, form.new_password)
    ElMessage.success('密码已修改')
    visible.value = false
    reset()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="修改密码" width="380px" @close="reset">
    <el-form label-width="90px">
      <el-form-item label="当前密码">
        <el-input v-model="form.current_password" type="password" show-password placeholder="请输入当前密码" />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="form.new_password" type="password" show-password placeholder="至少 6 位" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确认修改</el-button>
    </template>
  </el-dialog>
</template>
