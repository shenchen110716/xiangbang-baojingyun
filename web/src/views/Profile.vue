<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const tags = ref('装配,叉车,夜班')
const jobId = ref(''); const must = ref('装配'); const nice = ref('叉车')
const lat = ref('31.23'); const lon = ref('121.47')
const wage = ref('200')
const split = (s: string) => s.split(/[,，\s]+/).filter(Boolean)

const putTags = useAction(() => api('/api/profile/tags', { body: { tags: split(tags.value) } }))
const getTags = useAction(() => api('/api/profile/tags'))
const putJob = useAction(() => api(`/api/profile/jobs/${jobId.value}`, { method: 'PUT', body: {
  mustTags: split(must.value), niceTags: split(nice.value),
  lat: Number(lat.value), lon: Number(lon.value) } }))
const getJob = useAction(() => api(`/api/profile/jobs/${jobId.value}`))
const putPref = useAction(() => api('/api/profile/preference', { method: 'PUT', body: {
  expectedWageCents: Math.round(Number(wage.value) * 100),
  lat: Number(lat.value), lon: Number(lon.value) } }))
const getPref = useAction(() => api('/api/profile/preference'))
</script>

<template>
  <h1>画像与偏好</h1>
  <p class="sub">画像喂给推荐算法；标签与期望决定你能被推到哪些岗位</p>

  <div class="card">
    <h3>我的技能标签</h3>
    <div class="row">
      <div class="field"><label>标签（逗号分隔）</label><input v-model="tags" /></div>
      <div class="field" style="flex:none"><button @click="putTags.run()">保存</button></div>
      <div class="field" style="flex:none"><button class="ghost" @click="getTags.run()">查询</button></div>
    </div>
    <Result :loading="putTags.loading.value" :error="putTags.error.value" :data="putTags.done.value ? '' : null" ok-text="标签已保存" />
    <Result :loading="getTags.loading.value" :error="getTags.error.value" :data="getTags.data.value" />
  </div>

  <div class="card">
    <h3>我的求职偏好</h3>
    <div class="row">
      <div class="field" style="flex:0 0 140px"><label>期望日薪（元）</label><input v-model="wage" /></div>
      <div class="field" style="flex:0 0 120px"><label>纬度</label><input v-model="lat" /></div>
      <div class="field" style="flex:0 0 120px"><label>经度</label><input v-model="lon" /></div>
      <div class="field" style="flex:none"><button @click="putPref.run()">保存</button></div>
      <div class="field" style="flex:none"><button class="ghost" @click="getPref.run()">查询</button></div>
    </div>
    <Result :loading="putPref.loading.value" :error="putPref.error.value" :data="putPref.done.value ? '' : null" ok-text="偏好已保存" />
    <Result :loading="getPref.loading.value" :error="getPref.error.value" :data="getPref.data.value" />
  </div>

  <div class="card">
    <h3>岗位画像</h3>
    <p class="hint">设置岗位画像要求你是该岗位所属组织的法人代表</p>
    <div class="row">
      <div class="field" style="flex:0 0 110px"><label>岗位 ID</label><input v-model="jobId" /></div>
      <div class="field"><label>必需标签</label><input v-model="must" /></div>
      <div class="field"><label>加分标签</label><input v-model="nice" /></div>
      <div class="field" style="flex:none"><button :disabled="!jobId" @click="putJob.run()">保存</button></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!jobId" @click="getJob.run()">查询</button></div>
    </div>
    <Result :loading="putJob.loading.value" :error="putJob.error.value" :data="putJob.done.value ? '' : null" ok-text="岗位画像已保存" />
    <Result :loading="getJob.loading.value" :error="getJob.error.value" :data="getJob.data.value" />
  </div>
</template>
