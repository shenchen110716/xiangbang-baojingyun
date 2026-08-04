<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const realName = ref('')
const idNumber = ref('')
const verify = useAction(() => api('/api/identity/real-name',
  { method: 'PUT', body: { realName: realName.value.trim(), idNumber: idNumber.value.trim() } }))
</script>

<template>
  <h1>身份与实名</h1>
  <p class="sub">实名认证是后续报名、签协议、领工资的前置条件</p>

  <div class="card">
    <h3>实名认证</h3>
    <p class="hint">认证的永远是当前登录账号 —— 身份取自 token，请求体里填谁都没用（防越权）</p>
    <div class="row">
      <div class="field"><label>真实姓名</label><input v-model="realName" placeholder="姓名" /></div>
      <div class="field"><label>身份证号</label><input v-model="idNumber" placeholder="18 位" /></div>
      <div class="field" style="flex:none">
        <button :disabled="verify.loading.value || !realName || !idNumber" @click="verify.run()">提交认证</button>
      </div>
    </div>
    <Result :loading="verify.loading.value" :error="verify.error.value"
            :data="verify.done.value ? '' : null" ok-text="实名认证已提交" />
  </div>
</template>
