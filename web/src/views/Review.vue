<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const appId = ref(''); const tags = ref('准时,踏实'); const comment = ref('')
const submit = useAction(() => api(`/api/review/${appId.value}`, { body: {
  tags: tags.value.split(/[,，\s]+/).filter(Boolean), comment: comment.value.trim() } }))
const visible = useAction(() => api(`/api/review/${appId.value}`))
const credit = useAction(() => api('/api/review/credit'))
</script>

<template>
  <h1>评价与信用</h1>
  <p class="sub">双向评价：双方都评完才互相可见，避免看了对方再报复性打分</p>

  <div class="card">
    <h3>提交评价</h3>
    <div class="row">
      <div class="field" style="flex:0 0 130px"><label>报名单 ID</label><input v-model="appId" /></div>
      <div class="field"><label>标签（逗号分隔）</label><input v-model="tags" /></div>
    </div>
    <div class="field"><label>评语</label><textarea v-model="comment" placeholder="可空"></textarea></div>
    <div class="row">
      <button :disabled="!appId" @click="submit.run()">提交评价</button>
      <button class="ghost" :disabled="!appId" @click="visible.run()">查看可见评价</button>
    </div>
    <div style="height:12px"></div>
    <Result :loading="submit.loading.value" :error="submit.error.value" :data="submit.done.value ? '' : null" ok-text="评价已提交" />
    <Result :loading="visible.loading.value" :error="visible.error.value" :data="visible.data.value" />
  </div>

  <div class="card">
    <h3>我的信用分</h3>
    <button class="ghost" @click="credit.run()">查询</button>
    <div style="height:12px"></div>
    <Result :loading="credit.loading.value" :error="credit.error.value" :data="credit.data.value" />
  </div>
</template>
