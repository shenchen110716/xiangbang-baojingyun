<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const limit = ref('10'); const jobId = ref('')
const forMe = useAction(() => api(`/api/matching/jobs?limit=${encodeURIComponent(limit.value)}`))
const forJob = useAction(() => api(`/api/matching/workers/${jobId.value}?limit=${encodeURIComponent(limit.value)}`))
</script>

<template>
  <h1>智能推荐</h1>
  <p class="sub">双边推荐：给工人推岗位，给岗位推工人。推荐结果依赖画像，没设画像会推不出东西</p>

  <div class="card">
    <h3>推给我的岗位（求职端）</h3>
    <div class="row">
      <div class="field" style="flex:0 0 110px"><label>条数</label><input v-model="limit" /></div>
      <div class="field" style="flex:none"><button @click="forMe.run()">获取推荐</button></div>
    </div>
    <div v-if="Array.isArray(forMe.data.value) && !forMe.data.value.length" class="empty">
      没有推荐结果 —— 先去「画像与偏好」设置标签和期望
    </div>
    <Result :loading="forMe.loading.value" :error="forMe.error.value" :data="forMe.data.value" />
  </div>

  <div class="card">
    <h3>适合这个岗位的工人（企业端）</h3>
    <div class="row">
      <div class="field"><label>岗位 ID</label><input v-model="jobId" /></div>
      <div class="field" style="flex:none"><button :disabled="!jobId" @click="forJob.run()">获取推荐</button></div>
    </div>
    <Result :loading="forJob.loading.value" :error="forJob.error.value" :data="forJob.data.value" />
  </div>
</template>
