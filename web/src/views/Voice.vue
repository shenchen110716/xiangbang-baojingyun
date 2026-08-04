<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../use'
import Result from '../components/Result.vue'

const orgId = ref(''); const title = ref('装配工'); const headcount = ref('3')
const wage = ref('200'); const extra = ref('')
const sid = ref(''); const utterance = ref('确认发布')

const draft = useAction(() => api<{ sessionId: number }>('/api/voice/job/draft', { body: {
  orgId: Number(orgId.value), title: title.value.trim(),
  headcount: Number(headcount.value), wageCents: Math.round(Number(wage.value) * 100),
  extra: extra.value.trim() } }))
const confirm = useAction(() => api(`/api/voice/job/${sid.value}/confirm`, { body: { utterance: utterance.value.trim() } }))
const recall = useAction(() => api(`/api/voice/job/${sid.value}/recall`, { method: 'POST' }))
</script>

<template>
  <h1>语音发单</h1>
  <p class="sub">语音采集 → 生成草稿 → 口头确认后建岗</p>

  <div class="card note">
    <h3>两个已知缺口</h3>
    <p class="hint" style="margin:0">
      ① 这里填的<strong>名额会被丢弃</strong>，实际固定按 1 个建岗；
      ② <strong>撤回不会真的关闭岗位</strong>。都记在设计文档的「已知缺口」里，不是这个界面的问题。
    </p>
  </div>

  <div class="card">
    <h3>创建草稿</h3>
    <div class="row">
      <div class="field" style="flex:0 0 110px"><label>组织 ID</label><input v-model="orgId" /></div>
      <div class="field"><label>岗位标题</label><input v-model="title" /></div>
      <div class="field" style="flex:0 0 100px"><label>名额</label><input v-model="headcount" /></div>
      <div class="field" style="flex:0 0 130px"><label>日薪（元）</label><input v-model="wage" /></div>
    </div>
    <div class="field"><label>补充说明</label><input v-model="extra" placeholder="可空" /></div>
    <button :disabled="!orgId || !title" @click="draft.run()">生成草稿</button>
    <div style="height:12px"></div>
    <Result :loading="draft.loading.value" :error="draft.error.value" :data="draft.data.value" ok-text="草稿已生成" />
  </div>

  <div class="card">
    <h3>确认 / 撤回</h3>
    <div class="row">
      <div class="field" style="flex:0 0 130px"><label>会话 ID</label><input v-model="sid" /></div>
      <div class="field"><label>确认话术</label><input v-model="utterance" /></div>
      <div class="field" style="flex:none"><button :disabled="!sid" @click="confirm.run()">确认发布</button></div>
      <div class="field" style="flex:none"><button class="danger" :disabled="!sid" @click="recall.run()">撤回</button></div>
    </div>
    <Result :loading="confirm.loading.value" :error="confirm.error.value" :data="confirm.data.value" ok-text="已确认" />
    <Result :loading="recall.loading.value" :error="recall.error.value" :data="recall.done.value ? '' : null" ok-text="已撤回（注意：不会真的关闭岗位）" />
  </div>
</template>
