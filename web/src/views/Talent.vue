<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const tags = ref('装配'); const limit = ref('20'); const uid = ref('')
const search = useAction(() => {
  const qs = tags.value.split(/[,，\s]+/).filter(Boolean)
    .map(t => `tags=${encodeURIComponent(t)}`).join('&')
  return api(`/api/talent/search?${qs}&limit=${encodeURIComponent(limit.value)}`)
})
const load = useAction(() => api(`/api/talent/${uid.value}`))
</script>

<template>
  <h1>人才库</h1>
  <p class="sub">履约沉淀下来的人才档案，可按标签复用</p>

  <div class="card">
    <h3>按标签搜索</h3>
    <div class="row">
      <div class="field"><label>标签（逗号分隔）</label><input v-model="tags" /></div>
      <div class="field" style="flex:0 0 100px"><label>条数</label><input v-model="limit" /></div>
      <div class="field" style="flex:none"><button :disabled="!tags" @click="search.run()">搜索</button></div>
    </div>
    <div v-if="Array.isArray(search.data.value) && !search.data.value.length" class="empty">没有匹配的人才</div>
    <Result :loading="search.loading.value" :error="search.error.value" :data="search.data.value" />
  </div>

  <div class="card">
    <h3>查看档案</h3>
    <div class="row">
      <div class="field"><label>用户 ID</label><input v-model="uid" /></div>
      <div class="field" style="flex:none"><button class="ghost" :disabled="!uid" @click="load.run()">查询</button></div>
    </div>
    <Result :loading="load.loading.value" :error="load.error.value" :data="load.data.value" />
  </div>
</template>
