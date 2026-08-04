<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../../api'

const realName = ref(''); const idNumber = ref('')
const tags = ref('装配,叉车,夜班')
const wage = ref('200'); const lat = ref('31.23'); const lon = ref('121.47')
const credit = ref<any>(null)
const msg = ref(''); const err = ref('')

async function run(fn: () => Promise<any>, ok: string) {
  msg.value = ''; err.value = ''
  try { await fn(); msg.value = ok } catch (e: any) { err.value = e.message }
}
const split = (s: string) => s.split(/[,，\s]+/).filter(Boolean)

const verify = () => run(() => api('/api/identity/real-name', { method: 'PUT',
  body: { realName: realName.value.trim(), idNumber: idNumber.value.trim() } }), '实名认证已提交')
const saveTags = () => run(() => api('/api/profile/tags', { body: { tags: split(tags.value) } }), '技能标签已保存')
const savePref = () => run(() => api('/api/profile/preference', { method: 'PUT', body: {
  expectedWageCents: Math.round(Number(wage.value) * 100),
  lat: Number(lat.value), lon: Number(lon.value) } }), '求职偏好已保存')

onMounted(async () => {
  try { credit.value = await api('/api/review/credit') } catch { /* 没接过单就没有信用分 */ }
  try { const t = await api<any[]>('/api/profile/tags'); if (t?.length) tags.value = t.map((x: any) => x.tag ?? x).join(',') } catch {}
})
</script>

<template>
  <h1>画像与信用</h1>
  <p class="sub">实名是报名的前提；标签和期望决定你能被推荐到哪些岗位</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="grid">
    <div class="card">
      <h3>实名认证</h3>
      <p class="hint">认证的永远是当前登录账号——身份取自 token</p>
      <div class="field"><label>真实姓名</label><input v-model="realName" /></div>
      <div class="field"><label>身份证号</label><input v-model="idNumber" placeholder="18 位" /></div>
      <button :disabled="!realName || !idNumber" @click="verify">提交认证</button>
    </div>

    <div class="card">
      <h3>我的信用分</h3>
      <p class="hint">来自双向评价，未接单时还没有</p>
      <div style="font-size:30px;font-weight:600;color:var(--primary-dark)">{{ credit?.score ?? '—' }}</div>
    </div>
  </div>

  <div class="card">
    <h3>技能标签</h3>
    <div class="row">
      <div class="field"><label>标签（逗号分隔）</label><input v-model="tags" /></div>
      <div class="field" style="flex:none"><button @click="saveTags">保存</button></div>
    </div>
  </div>

  <div class="card">
    <h3>求职偏好</h3>
    <div class="row">
      <div class="field" style="flex:0 0 150px"><label>期望日薪（元）</label><input v-model="wage" /></div>
      <div class="field" style="flex:0 0 120px"><label>纬度</label><input v-model="lat" /></div>
      <div class="field" style="flex:0 0 120px"><label>经度</label><input v-model="lon" /></div>
      <div class="field" style="flex:none"><button @click="savePref">保存</button></div>
    </div>
  </div>
</template>
