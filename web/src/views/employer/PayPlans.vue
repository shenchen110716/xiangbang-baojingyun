<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { api, yuan } from '../../api'
import { zhStatus, statusTone, zhPayType, zhFactorType } from '../../i18n'

/**
 * 计薪方案。
 *
 * <p>方案**版本不可变**:每次保存都是发一个新版本,旧版自动失效但不删除 ——
 * 已经出的工资单还要靠它解释金额。界面上要让人看到这一点,
 * 否则人会以为「改方案」是就地修改,进而以为历史工资也跟着变了。
 */

const jobs = ref<any[]>([])
const jobId = ref('')
const plans = ref<any[]>([])
const loading = ref(true)
const err = ref(''); const msg = ref('')
const saving = ref(false)

const name = ref('')
const payType = ref('HOURLY')
const basic = ref('25')
const float_ = ref('5')
const fixed = ref('0')
const from = ref(new Date().toISOString().slice(0, 10))
const factors = ref<{ factorType: string; name: string; amount: string }[]>([])

const PAY_TYPES = [
  { v: 'HOURLY',  label: '按小时', unit: '每小时' },
  { v: 'DAILY',   label: '按天',   unit: '每天' },
  { v: 'MONTHLY', label: '按月',   unit: '每月' },
]

const unit = computed(() => PAY_TYPES.find(p => p.v === payType.value)?.unit ?? '')
const active = computed(() => plans.value.find(p => p.status === 'ACTIVE'))

async function load() {
  loading.value = true; err.value = ''
  try {
    jobs.value = await api('/api/job/mine')
    if (!jobId.value && jobs.value.length) jobId.value = String(jobs.value[0].id)
    await loadPlans()
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

async function loadPlans() {
  if (!jobId.value) { plans.value = []; return }
  err.value = ''
  try { plans.value = await api(`/api/settlement/job/${jobId.value}/pay-plans`) }
  catch (e: any) { err.value = e.message; plans.value = [] }
}

function addFactor() {
  factors.value.push({ factorType: 'BONUS', name: '', amount: '' })
}

/** 金额一律存正数、方向由类型决定 —— 让两种写法并存,迟早有人加错符号。 */
function toCents(v: string) { return Math.round(Number(v) * 100) }

const canSave = computed(() =>
  !!jobId.value && !!name.value.trim() &&
  (toCents(basic.value) > 0 || toCents(float_.value) > 0 || toCents(fixed.value) > 0) &&
  factors.value.every(f => f.name.trim() && Number(f.amount) > 0))

async function publish() {
  msg.value = ''; err.value = ''; saving.value = true
  try {
    const r = await api<{ id: number }>(`/api/settlement/job/${jobId.value}/pay-plan`, { body: {
      name: name.value.trim(), payType: payType.value,
      basicSalaryCents: toCents(basic.value),
      floatSalaryCents: toCents(float_.value),
      fixedSalaryCents: toCents(fixed.value),
      effectiveFrom: from.value,
      factors: factors.value.map(f => ({
        factorType: f.factorType, name: f.name.trim(), amountCents: toCents(f.amount) })),
    } })
    msg.value = `新版本 #${r.id} 已发布并生效，上一版已自动失效（但保留，用于解释历史工资单）`
    name.value = ''; factors.value = []
    await loadPlans()
  } catch (e: any) { err.value = e.message }
  finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <h1>计薪方案</h1>
  <p class="sub">
    按岗位设定工资怎么算。<b>同一岗位同时只有一个方案生效</b>；
    每次保存都是发新版本，旧版保留但失效
  </p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card">
    <div class="row" style="justify-content:space-between;align-items:flex-end">
      <div class="field" style="flex:1">
        <label>选择岗位</label>
        <select v-model="jobId" @change="loadPlans">
          <option v-for="j in jobs" :key="j.id" :value="String(j.id)">{{ j.title }}（#{{ j.id }}）</option>
        </select>
      </div>
      <button class="ghost sm" @click="load">刷新</button>
    </div>
  </div>

  <div v-if="loading" class="msg info">加载中…</div>
  <div v-else-if="!jobs.length" class="card note">
    <h3>还没有发布过岗位</h3>
    <p class="hint" style="margin:0">计薪方案挂在岗位上，先去「我的岗位」发布岗位。</p>
  </div>

  <template v-else>
    <div v-if="!active" class="card note">
      <h3>这个岗位还没有计薪方案</h3>
      <p class="hint" style="margin:0">
        没有方案时，工资按岗位上那个<b>日薪一口价</b>发，和考勤无关。
        设了方案之后才会按「已确认工时 × 方案」算。
      </p>
    </div>

    <div class="card">
      <h3>方案版本</h3>
      <div v-if="!plans.length" class="empty">还没有发布过方案</div>
      <table v-else>
        <thead>
          <tr><th style="width:70px">版本</th><th>名称</th><th style="width:90px">方式</th>
              <th style="width:110px">基本</th><th style="width:110px">浮动</th><th style="width:110px">固定</th>
              <th style="width:90px">状态</th><th style="width:190px">生效期间</th></tr>
        </thead>
        <tbody>
          <tr v-for="p in plans" :key="p.id">
            <td>v{{ p.version }}</td>
            <td>
              <div style="font-weight:550">{{ p.name }}</div>
              <div v-if="p.factors?.length" style="color:var(--muted);font-size:12.5px">
                <span v-for="(f, i) in p.factors" :key="i">
                  {{ i ? ' · ' : '' }}{{ zhFactorType(f.factorType) }}「{{ f.name }}」{{ yuan(f.amountCents) }}
                </span>
              </div>
            </td>
            <td>{{ zhPayType(p.payType) }}</td>
            <td>{{ yuan(p.basicSalaryCents) }}</td>
            <td>{{ yuan(p.floatSalaryCents) }}</td>
            <td>{{ yuan(p.fixedSalaryCents) }}</td>
            <td><span class="tag" :class="statusTone(p.status)">{{ zhStatus(p.status) }}</span></td>
            <td style="color:var(--muted);font-size:12.5px">
              {{ p.effectiveFrom }} 起<span v-if="p.effectiveTo">，至 {{ p.effectiveTo }} 止</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h3>发布新版本</h3>
      <p class="hint">
        浮动工资是<b>佣金的计算基数</b>（和老系统口径一致），不是拿应发总额算的。
      </p>
      <div class="row">
        <div class="field" style="flex:1"><label>方案名称</label>
          <input v-model="name" placeholder="如：装配工 · 2026 春季调薪" /></div>
        <div class="field" style="flex:0 0 140px"><label>计薪方式</label>
          <select v-model="payType">
            <option v-for="t in PAY_TYPES" :key="t.v" :value="t.v">{{ t.label }}</option>
          </select>
        </div>
        <div class="field" style="flex:0 0 150px"><label>生效日期</label>
          <input type="date" v-model="from" /></div>
      </div>
      <div class="row">
        <div class="field"><label>基本工资（元，{{ unit }}）</label><input v-model="basic" /></div>
        <div class="field"><label>浮动工资（元，{{ unit }}）</label><input v-model="float_" /></div>
        <div class="field"><label>固定工资（元，整单）</label><input v-model="fixed" /></div>
      </div>

      <div class="field">
        <label>调整项（奖励加钱，扣款与罚款减钱）</label>
        <div v-for="(f, i) in factors" :key="i" class="row" style="gap:8px;margin-bottom:6px">
          <select v-model="f.factorType" style="flex:0 0 110px">
            <option value="BONUS">奖励</option>
            <option value="DEDUCTION">扣款</option>
            <option value="PENALTY">罚款</option>
          </select>
          <input v-model="f.name" placeholder="名称，如：全勤奖 / 宿舍水电" style="flex:1" />
          <input v-model="f.amount" placeholder="金额（元）" style="flex:0 0 130px" />
          <button class="ghost sm" @click="factors.splice(i, 1)">删除</button>
        </div>
        <button class="ghost sm" @click="addFactor">+ 加一项</button>
      </div>

      <p class="hint">
        三项工资<b>至少要有一项不为零</b>。全零方案算出来永远是 0 工资，
        而且要等到发工资那天才会发现。
      </p>
      <button :disabled="!canSave || saving" @click="publish">
        {{ saving ? '发布中…' : '发布新版本' }}
      </button>
    </div>
  </template>
</template>
