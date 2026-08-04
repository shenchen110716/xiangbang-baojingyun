<script setup lang="ts">
import { ref } from 'vue'
import { api, yuan } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const orgId = ref(''); const title = ref(''); const desc = ref(''); const wage = ref('200')
const jobId = ref('')

// 后端一律以「分」为单位。这里做一次元→分的转换,并且**只在提交前做一次**,
// 不在界面上来回换算 —— 金额换算写两处迟早对不上。
const post = useAction(() => api<{ id: number }>('/api/job', { body: {
  orgId: Number(orgId.value), title: title.value.trim(),
  description: desc.value.trim(), wageCents: Math.round(Number(wage.value) * 100),
}}))
const load = useAction(() => api(`/api/job/${jobId.value}`))
</script>

<template>
  <h1>岗位</h1>
  <p class="sub">发岗要求组织已通过审核，且你是它的法人代表</p>

  <div class="card">
    <h3>发布岗位</h3>
    <div class="row">
      <div class="field" style="flex:0 0 110px"><label>组织 ID</label><input v-model="orgId" /></div>
      <div class="field"><label>岗位标题</label><input v-model="title" placeholder="如：装配工 · 白班" /></div>
      <div class="field" style="flex:0 0 130px"><label>日薪（元）</label><input v-model="wage" /></div>
    </div>
    <div class="field"><label>岗位描述</label><textarea v-model="desc" placeholder="工作内容、要求、地点"></textarea></div>
    <button :disabled="post.loading.value || !orgId || !title || !desc" @click="post.run()">发布</button>
    <div style="height:12px"></div>
    <Result :loading="post.loading.value" :error="post.error.value" :data="post.data.value" ok-text="岗位已发布" />
  </div>

  <div class="card">
    <h3>查询岗位</h3>
    <div class="row">
      <div class="field"><label>岗位 ID</label><input v-model="jobId" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!jobId" @click="load.run()">查询</button></div>
    </div>
    <div v-if="load.data.value" class="row" style="margin-bottom:10px">
      <span class="tag">日薪 {{ yuan((load.data.value as any).wageCents) }}</span>
      <span class="tag">{{ (load.data.value as any).status }}</span>
    </div>
    <Result :loading="load.loading.value" :error="load.error.value" :data="load.data.value" />
  </div>
</template>
