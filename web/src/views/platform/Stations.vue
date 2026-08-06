<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { zhStatus, statusTone } from '../../i18n'
import { api, when, yuan } from '../../api'

type Station = {
  orgId: number; name: string; legalRepUserId: number
  stationPercent: number | null; effectivePercent: number
  approvedAt: string; brokerCount: number
}
type Node = {
  userId: number; stationOrgId: number | null; parentUserId: number | null
  lastActiveAt: string; status: string; childCount: number
}

const stations = ref<Station[]>([])
const brokers = ref<Node[]>([])
const changes = ref<any[]>([])
const loading = ref(true)
const err = ref(''); const msg = ref(''); const busy = ref('')

/** 展开中的服务站 → 它下面的业务员。 */
const expanded = ref<number | null>(null)

/** 正在编辑比例的服务站。 */
const editing = ref<Station | null>(null)
const draftPercent = ref('')
const followDefault = ref(false)
const reason = ref('')

/** 正在调整归属的业务员。 */
const moving = ref<Node | null>(null)
const moveKind = ref<'station' | 'parent'>('station')
const moveTarget = ref('')
const moveReason = ref('')

async function load() {
  loading.value = true; err.value = ''
  try {
    stations.value = await api('/api/broker/stations')
    brokers.value = await api('/api/broker/salesmen')
    changes.value = await api('/api/broker/salesmen/changes')
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

function startEditPercent(s: Station) {
  editing.value = s
  followDefault.value = s.stationPercent === null
  draftPercent.value = String(s.stationPercent ?? s.effectivePercent)
  reason.value = ''; msg.value = ''; err.value = ''
}

async function savePercent() {
  if (!editing.value) return
  busy.value = 'percent'; err.value = ''; msg.value = ''
  try {
    await api(`/api/broker/stations/${editing.value.orgId}/percent`, { method: 'PUT', body: {
      // null 表示"跟随平台默认"，和 0 完全不是一回事
      percent: followDefault.value ? null : Number(draftPercent.value),
      reason: reason.value.trim() } })
    msg.value = followDefault.value
      ? `「${editing.value.name}」改为跟随平台默认`
      : `「${editing.value.name}」佣金比例改为 ${draftPercent.value}%`
    editing.value = null
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

function startMove(n: Node, kind: 'station' | 'parent') {
  moving.value = n; moveKind.value = kind
  moveTarget.value = String((kind === 'station' ? n.stationOrgId : n.parentUserId) ?? '')
  moveReason.value = ''; msg.value = ''; err.value = ''
}

async function saveMove() {
  if (!moving.value) return
  busy.value = 'move'; err.value = ''; msg.value = ''
  try {
    const t = moveTarget.value.trim()
    await api(`/api/broker/salesmen/${moving.value.userId}/${moveKind.value}`, {
      method: 'PUT', body: { targetId: t === '' ? null : Number(t), reason: moveReason.value.trim() } })
    msg.value = `业务员 #${moving.value.userId} 的${moveKind.value === 'station' ? '服务站' : '上级'}已调整`
    moving.value = null
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function runDemotion() {
  busy.value = 'demote'; err.value = ''; msg.value = ''
  try {
    const r = await api<{ processed: number }>('/api/broker/salesmen/run-demotion', { method: 'POST' })
    msg.value = r.processed === 0
      ? '没有需要降级的业务员'
      : `已处理 ${r.processed} 名业务员（有下级的被架空并上提，无下级的标记降级）`
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

const CHANGE_TYPE: Record<string, string> = { STATION: '服务站', PARENT: '上级', STATUS: '状态' }
const inStation = (orgId: number) => brokers.value.filter(b => b.stationOrgId === orgId)
onMounted(() => { load(); loadDefaults(); loadDefaultStation(); loadDefaultSchemes() })

/** 联合关系(老系统 M10 §3.4)。展开某个站时才拉,免得一进页面就打一串请求。 */
/**
 * 平台默认服务站。**从列表里点选,不让人手填编号** ——
 * 填错的后果是自动升级的业务员归不到这个站,而那不会报错,只会静默少发一笔分成。
 */
const defaultStationId = ref<number>(0)

async function loadDefaultStation() {
  try {
    const all = await api<any[]>('/api/ops/settings')
    const row = all.find(x => x.key === 'broker.default.station.org.id')
    defaultStationId.value = Number(row?.value ?? 0)
  } catch { defaultStationId.value = 0 }
}

const manageOpen = ref<number | null>(null)
async function toggleManage(orgId: number) {
  if (manageOpen.value === orgId) { manageOpen.value = null; return }
  manageOpen.value = orgId
  await loadRates(orgId)
  await loadSchemes(orgId)
  await loadCoops(orgId)
}

const jointsOf = ref<Record<number, any[]>>({})
const jointOpen = ref<number | null>(null)
const jointTo = ref(''); const jointRate = ref('30')

async function toggleJoints(orgId: number) {
  if (jointOpen.value === orgId) { jointOpen.value = null; return }
  jointOpen.value = orgId
  await loadJoints(orgId)
}

async function loadJoints(orgId: number) {
  err.value = ''
  try { jointsOf.value[orgId] = await api(`/api/broker/joints/station/${orgId}`) }
  catch (e: any) { err.value = e.message; jointsOf.value[orgId] = [] }
}

async function applyJoint(fromOrgId: number) {
  msg.value = ''; err.value = ''; busy.value = `joint-${fromOrgId}`
  try {
    await api('/api/broker/joints', { body: {
      fromOrgId, toOrgId: Number(jointTo.value), ratePercent: Number(jointRate.value) } })
    msg.value = `已向服务站 #${jointTo.value} 发起联合申请，等对方确认`
    jointTo.value = ''
    await loadJoints(fromOrgId)
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function jointAct(orgId: number, id: number, what: 'confirm' | 'cancel' | 'end', label: string) {
  msg.value = ''; err.value = ''; busy.value = `j-${id}`
  try {
    await api(`/api/broker/joints/${id}/${what}`, { method: 'PUT' })
    msg.value = `联合 #${id} ${label}`
    await loadJoints(orgId)
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}


/** 建站与站长指派(平台端统一管理)。 */
const newName = ref(''); const newCode = ref('')
/** 公司 or 个人。老板 2026-08-06:服务站两者都可以。 */
const newSubject = ref<'COMPANY' | 'INDIVIDUAL'>('COMPANY')
const newPerson = ref(''); const newAddress = ref('')
const masterOf = ref<Record<number, string>>({})
const masterReason = ref<Record<number, string>>({})

async function createStation() {
  msg.value = ''; err.value = ''; busy.value = 'create'
  try {
    const individual = newSubject.value === 'INDIVIDUAL'
    // 两个端点而不是一个带可选字段的 —— 一个端点里"代码可空、人可空"的话，
    // 两个都不填也能过，建出来一个既不是公司也说不清是谁的站
    const r = individual
      ? await api<{ id: number }>('/api/org/stations/individual', {
          body: { name: newName.value.trim(), personUserId: Number(newPerson.value),
                  address: newAddress.value.trim() || null } })
      : await api<{ id: number }>('/api/org/stations', {
          body: { name: newName.value.trim(), creditCode: newCode.value.trim() } })
    msg.value = individual
      ? `个人服务站 #${r.id} 已设立，主体人是用户 #${newPerson.value}`
      : `服务站 #${r.id} 已设立。**还没有站长**，请指派后它才能签联合协议`
    newName.value = ''; newCode.value = ''; newPerson.value = ''; newAddress.value = ''
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function assignMaster(orgId: number, clear = false) {
  const reason = masterReason.value[orgId]?.trim()
  if (!reason) { err.value = '请填写变更原因'; return }
  msg.value = ''; err.value = ''; busy.value = `master-${orgId}`
  try {
    await api(`/api/org/stations/${orgId}/master`, { method: 'PUT', body: {
      userId: clear ? null : Number(masterOf.value[orgId]), reason } })
    msg.value = clear ? `服务站 #${orgId} 已撤下站长` : `服务站 #${orgId} 的站长已更新`
    masterOf.value[orgId] = ''; masterReason.value[orgId] = ''
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

/** 按业务类目的分成比例。 */
const CATEGORIES = [
  { v: 'JOB', label: '岗位' },
  { v: 'PRODUCT', label: '商品' },
  { v: 'TRAINING', label: '培训' },
]
const ratesOf = ref<Record<number, any[]>>({})
const defaultRates = ref<any[]>([])
const rateCat = ref('JOB'); const ratePct = ref(''); const rateReason = ref('')

async function loadRates(orgId: number) {
  try { ratesOf.value[orgId] = await api(`/api/broker/rates/${orgId}`) }
  catch (e: any) { err.value = e.message; ratesOf.value[orgId] = [] }
}

async function loadDefaults() {
  try { defaultRates.value = await api('/api/broker/rates/defaults') }
  catch { defaultRates.value = [] }
}

async function setRate(orgId: number | null) {
  msg.value = ''; err.value = ''; busy.value = `rate-${orgId ?? 'default'}`
  try {
    await api('/api/broker/rates', { method: 'PUT', body: {
      stationOrgId: orgId, category: rateCat.value,
      percent: Number(ratePct.value), reason: rateReason.value.trim() } })
    msg.value = orgId ? `服务站 #${orgId} 的${rateCat.value}比例已更新` : '平台默认比例已更新'
    ratePct.value = ''; rateReason.value = ''
    if (orgId) await loadRates(orgId); else await loadDefaults()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

/** 站长授权业务员。 */
const grantUser = ref<Record<number, string>>({})
async function grantBroker(orgId: number) {
  msg.value = ''; err.value = ''; busy.value = `grant-${orgId}`
  try {
    await api('/api/broker/salesmen/grant', {
      body: { stationOrgId: orgId, userId: Number(grantUser.value[orgId]) } })
    msg.value = `用户 #${grantUser.value[orgId]} 已成为服务站 #${orgId} 的业务员`
    grantUser.value[orgId] = ''
    await load()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

const zhCategory = (v: string) => CATEGORIES.find(c => c.v === v)?.label ?? v


/**
 * 按类目的**整套**分配方案(主动/平台/被动/服务站/逐级/下限)。
 *
 * <p>此前界面上只有"服务站比例"一个数,而分账其实有六档 ——
 * 运营在界面上看到的和实际生效的不是一回事。
 */
const schemesOf = ref<Record<number, any[]>>({})
const defaultSchemes = ref<any[]>([])
const schemeForm = ref({
  category: 'JOB', activePct: '60', platformPct: '20', passivePct: '30',
  stationPct: '50', passiveStepPct: '30', minPayoutYuan: '1', reason: '',
})

/** 平台 + 被动 + 服务站在同一块「剩余」里分,界面上先算给人看。 */
const remainderSum = computed(() =>
  Number(schemeForm.value.platformPct || 0) +
  Number(schemeForm.value.passivePct || 0) +
  Number(schemeForm.value.stationPct || 0))

async function loadDefaultSchemes() {
  try { defaultSchemes.value = await api('/api/broker/schemes/defaults') }
  catch { defaultSchemes.value = [] }
}

async function loadSchemes(orgId: number) {
  try { schemesOf.value[orgId] = await api(`/api/broker/schemes/${orgId}`) }
  catch (e: any) { err.value = e.message; schemesOf.value[orgId] = [] }
}

async function saveScheme(orgId: number | null) {
  msg.value = ''; err.value = ''; busy.value = `scheme-${orgId ?? 'default'}`
  try {
    const f = schemeForm.value
    await api('/api/broker/schemes', { method: 'PUT', body: {
      stationOrgId: orgId, category: f.category,
      activePct: Number(f.activePct), platformPct: Number(f.platformPct),
      passivePct: Number(f.passivePct), stationPct: Number(f.stationPct),
      passiveStepPct: Number(f.passiveStepPct),
      minPayoutCents: Math.round(Number(f.minPayoutYuan) * 100),
      reason: f.reason.trim() } })
    msg.value = orgId ? `服务站 #${orgId} 的${zhCategory(f.category)}方案已更新` : `平台默认${zhCategory(f.category)}方案已更新`
    f.reason = ''
    if (orgId) await loadSchemes(orgId); else await loadDefaultSchemes()
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

/** 合作关系与操作员。 */
const coopsOf = ref<Record<number, any[]>>({})
const operatorsOf = ref<Record<number, any[]>>({})
const coopPartner = ref(''); const coopOperator = ref<Record<number, string>>({})

async function loadCoops(orgId: number) {
  try { coopsOf.value[orgId] = await api(`/api/broker/cooperations/org/${orgId}`) }
  catch (e: any) { err.value = e.message; coopsOf.value[orgId] = [] }
}

async function applyCoop(stationOrgId: number) {
  msg.value = ''; err.value = ''; busy.value = `coop-${stationOrgId}`
  try {
    await api('/api/broker/cooperations', { body: {
      stationOrgId, partnerOrgId: Number(coopPartner.value), initiatedByStation: true } })
    msg.value = `已向用工单位 #${coopPartner.value} 发起合作申请，等对方确认`
    coopPartner.value = ''
    await loadCoops(stationOrgId)
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function coopAct(orgId: number, id: number, what: 'confirm' | 'cancel' | 'end', label: string) {
  msg.value = ''; err.value = ''; busy.value = `c-${id}`
  try {
    await api(`/api/broker/cooperations/${id}/${what}`, { method: 'PUT' })
    msg.value = `合作 #${id} ${label}`
    await loadCoops(orgId)
    delete operatorsOf.value[id]
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function loadOperators(coopId: number) {
  try { operatorsOf.value[coopId] = await api(`/api/broker/cooperations/${coopId}/operators`) }
  catch (e: any) { err.value = e.message; operatorsOf.value[coopId] = [] }
}

async function assignOperator(coopId: number) {
  msg.value = ''; err.value = ''; busy.value = `op-${coopId}`
  try {
    await api(`/api/broker/cooperations/${coopId}/operators`, {
      body: { userId: Number(coopOperator.value[coopId]) } })
    msg.value = `用户 #${coopOperator.value[coopId]} 已成为该合作的操作员`
    coopOperator.value[coopId] = ''
    await loadOperators(coopId)
  } catch (e: any) { err.value = e.message }
  finally { busy.value = '' }
}

async function revokeOperator(coopId: number, userId: number) {
  msg.value = ''; err.value = ''
  try {
    await api(`/api/broker/cooperations/${coopId}/operators/${userId}`, { method: 'DELETE' })
    msg.value = `已解绑操作员 #${userId}`
    await loadOperators(coopId)
  } catch (e: any) { err.value = e.message }
}

</script>

<template>
  <h1>服务站管理</h1>
  <p class="sub">服务站的佣金比例、下属业务员的挂靠与上级关系。所有调整都留痕</p>

  <div v-if="msg" class="msg ok">{{ msg }}</div>
  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card note">
    <h3>比例留空 = 跟随平台默认</h3>
    <p class="hint" style="margin:0">
      这和「设成 0」完全不是一回事。留空的站会随平台默认值一起变；
      单独设过的站不会。所以<strong>不要把默认值手工抄进来</strong>——抄了之后
      「没设过」和「设成和默认一样」就再也分不清了。
    </p>
  </div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:12px">
      <h3 style="margin:0">服务站</h3>
      <button class="ghost sm" @click="load">刷新</button>
    </div>
    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!stations.length" class="empty">
      还没有服务站。服务站是「组织入驻」里主体类型选「服务站」并通过审核后自动出现的
    </div>
    <table v-else>
      <thead>
        <tr><th>服务站</th><th style="width:130px">佣金比例</th><th style="width:100px">业务员</th>
            <th style="width:150px">入驻</th><th style="width:150px"></th></tr>
      </thead>
      <tbody>
        <template v-for="s in stations" :key="s.orgId">
          <tr>
            <td>
              <div style="font-weight:550">{{ s.name }}</div>
              <div style="color:var(--muted);font-size:12.5px">#{{ s.orgId }} · 法人 #{{ s.legalRepUserId }}</div>
            </td>
            <td>
              <span class="tag" :class="s.stationPercent === null ? '' : 'ok'">
                {{ s.effectivePercent }}%{{ s.stationPercent === null ? '（默认）' : '' }}
              </span>
            </td>
            <td>{{ s.brokerCount }} 人</td>
            <td style="color:var(--muted);font-size:12.5px">{{ when(s.approvedAt) }}</td>
            <td>
              <div class="row" style="gap:6px">
                <button class="ghost sm" @click="startEditPercent(s)">改比例</button>
                <button class="ghost sm" @click="expanded = expanded === s.orgId ? null : s.orgId">
                  {{ expanded === s.orgId ? '收起' : '业务员' }}
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="expanded === s.orgId">
            <td colspan="5" style="background:var(--surface-2);padding:12px">
              <div v-if="!inStation(s.orgId).length" class="empty" style="padding:8px 0">这个站下面还没有业务员</div>
              <table v-else>
                <thead><tr><th style="width:110px">业务员</th><th style="width:110px">上级</th>
                           <th style="width:90px">下级数</th><th style="width:150px">最近活跃</th>
                           <th style="width:90px">状态</th><th></th></tr></thead>
                <tbody>
                  <tr v-for="b in inStation(s.orgId)" :key="b.userId">
                    <td>#{{ b.userId }}</td>
                    <td>{{ b.parentUserId === null ? '根业务员' : '#' + b.parentUserId }}</td>
                    <td>{{ b.childCount }}</td>
                    <td style="color:var(--muted);font-size:12.5px">{{ when(b.lastActiveAt) }}</td>
                    <td><span class="tag" :class="b.status === 'ACTIVE' ? 'ok' : 'bad'">
                      {{ b.status === 'ACTIVE' ? '正常' : '已降级' }}</span></td>
                    <td>
                      <div class="row" style="gap:6px">
                        <button class="ghost sm" @click="startMove(b, 'parent')">改上级</button>
                        <button class="ghost sm" @click="startMove(b, 'station')">改服务站</button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>

  <div v-if="editing" class="card" style="border-color:var(--primary)">
    <h3>「{{ editing.name }}」佣金比例</h3>
    <div class="field">
      <label>
        <input type="checkbox" v-model="followDefault" style="width:auto;margin-right:6px" />
        跟随平台默认（当前 {{ editing.effectivePercent }}%）
      </label>
    </div>
    <div class="row">
      <div class="field" style="flex:0 0 160px">
        <label>比例（%）</label>
        <input v-model="draftPercent" :disabled="followDefault" />
      </div>
      <div class="field"><label>调整理由（必填）</label><input v-model="reason" placeholder="事后要能解释" /></div>
      <div class="field" style="flex:none">
        <button :disabled="busy === 'percent' || !reason.trim()" @click="savePercent">保存</button>
      </div>
      <div class="field" style="flex:none"><button class="ghost" @click="editing = null">取消</button></div>
    </div>
  </div>

  <div v-if="moving" class="card" style="border-color:var(--primary)">
    <h3>调整业务员 #{{ moving.userId }} 的{{ moveKind === 'station' ? '服务站' : '上级' }}</h3>
    <p class="hint">
      留空表示{{ moveKind === 'station' ? '从服务站摘除' : '变成根业务员（根不参与降级）' }}
    </p>
    <div class="row">
      <div class="field" style="flex:0 0 190px">
        <label>{{ moveKind === 'station' ? '服务站 ID' : '上级业务员 ID' }}</label>
        <input v-model="moveTarget" placeholder="留空即清除" />
      </div>
      <div class="field"><label>调整理由（必填）</label><input v-model="moveReason" /></div>
      <div class="field" style="flex:none">
        <button :disabled="busy === 'move' || !moveReason.trim()" @click="saveMove">保存</button>
      </div>
      <div class="field" style="flex:none"><button class="ghost" @click="moving = null">取消</button></div>
    </div>
  </div>

  <div class="card note">
    <h3>降级规则</h3>
    <p class="hint" style="margin:0 0 12px">
      定时任务每天凌晨 3 点跑。超过「业务员降级天数」没有活跃的：
      <strong>有下级的被架空</strong>（下级上提到他的上级，他自己活跃时间重置，相当于缓刑一轮）；
      <strong>无下级的标记降级</strong>——注意是标记，不删除，否则他名下已产生的佣金归属就断了。
      <strong>根业务员永不降级。</strong>天数在「参数设置」里改。
    </p>
    <button :disabled="busy === 'demote'" @click="runDemotion">
      {{ busy === 'demote' ? '处理中…' : '立即跑一次降级' }}
    </button>
  </div>

  <div class="card">
    <h3>全部业务员</h3>
    <div v-if="!brokers.length" class="empty">还没有业务员</div>
    <table v-else>
      <thead><tr><th style="width:110px">业务员</th><th style="width:130px">服务站</th>
                 <th style="width:110px">上级</th><th style="width:90px">下级数</th>
                 <th style="width:150px">最近活跃</th><th style="width:90px">状态</th></tr></thead>
      <tbody>
        <tr v-for="b in brokers" :key="b.userId">
          <td>#{{ b.userId }}</td>
          <td>{{ b.stationOrgId === null ? '未挂靠' : '#' + b.stationOrgId }}</td>
          <td>{{ b.parentUserId === null ? '根业务员' : '#' + b.parentUserId }}</td>
          <td>{{ b.childCount }}</td>
          <td style="color:var(--muted);font-size:12.5px">{{ when(b.lastActiveAt) }}</td>
          <td><span class="tag" :class="b.status === 'ACTIVE' ? 'ok' : 'bad'">
            {{ b.status === 'ACTIVE' ? '正常' : '已降级' }}</span></td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="card">
    <h3>归属变更记录</h3>
    <p class="hint">最近 100 条。系统自动降级时没有操作人</p>
    <div v-if="!changes.length" class="empty">还没有变更</div>
    <table v-else>
      <thead><tr><th style="width:150px">时间</th><th style="width:110px">业务员</th>
                 <th style="width:90px">类型</th><th style="width:150px">变更</th>
                 <th style="width:90px">操作人</th><th>理由</th></tr></thead>
      <tbody>
        <tr v-for="c in changes" :key="c.id">
          <td style="color:var(--muted);font-size:12.5px">{{ when(c.changedAt) }}</td>
          <td>#{{ c.brokerUserId }}</td>
          <td>{{ CHANGE_TYPE[c.changeType] ?? c.changeType }}</td>
          <td style="font-family:var(--mono);font-size:12.5px">
            {{ c.oldValue ?? '空' }} → {{ c.newValue ?? '空' }}
          </td>
          <td>{{ c.changedBy === null ? '系统' : '#' + c.changedBy }}</td>
          <td>{{ c.reason }}</td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="card">
    <h3>平台默认分配方案</h3>
    <p class="hint">
      对所有没单独设过的服务站生效。<b>六档一整套，按业务类目分别设</b>——
      岗位、商品、培训的分账结构本来就不同：商品可能没有被动佣金，培训可能主动佣金极高。
      用同一套比例去分，任何一个类目都是错的。
    </p>
    <div v-if="!defaultSchemes.length" class="empty" style="padding:8px 0">还没有设过</div>
    <table v-else style="margin-bottom:12px">
      <thead><tr><th style="width:90px">类目</th><th style="width:80px">主动</th>
                 <th style="width:80px">平台</th><th style="width:80px">被动</th>
                 <th style="width:90px">服务站</th><th style="width:80px">逐级</th>
                 <th style="width:90px">下限</th></tr></thead>
      <tbody>
        <tr v-for="x in defaultSchemes" :key="x.category">
          <td>{{ zhCategory(x.category) }}</td>
          <td>{{ x.activePct }}%</td><td>{{ x.platformPct }}%</td>
          <td>{{ x.passivePct }}%</td><td>{{ x.stationPct }}%</td>
          <td>{{ x.passiveStepPct }}%</td><td>{{ yuan(x.minPayoutCents) }}</td>
        </tr>
      </tbody>
    </table>

    <div class="row">
      <div class="field" style="flex:0 0 120px"><label>类目</label>
        <select v-model="schemeForm.category">
          <option v-for="c in CATEGORIES" :key="c.v" :value="c.v">{{ c.label }}</option>
        </select>
      </div>
      <div class="field"><label>主动佣金 %</label><input v-model="schemeForm.activePct" /></div>
      <div class="field"><label>平台 %</label><input v-model="schemeForm.platformPct" /></div>
      <div class="field"><label>被动 %</label><input v-model="schemeForm.passivePct" /></div>
      <div class="field"><label>服务站 %</label><input v-model="schemeForm.stationPct" /></div>
    </div>
    <div class="row">
      <div class="field" style="flex:0 0 140px"><label>被动逐级 %</label>
        <input v-model="schemeForm.passiveStepPct" /></div>
      <div class="field" style="flex:0 0 150px"><label>分账下限（元）</label>
        <input v-model="schemeForm.minPayoutYuan" /></div>
      <div class="field"><label>调整原因<span style="color:var(--bad)">*</span></label>
        <input v-model="schemeForm.reason" placeholder="必填，事后查得到是谁改的" /></div>
    </div>
    <p class="hint" :style="{ color: remainderSum > 100 ? 'var(--bad,#f87171)' : undefined }">
      平台 + 被动 + 服务站 = <b>{{ remainderSum }}%</b>。
      它们在同一块「剩余」（基数 − 主动）里分，<b>相加不能超过 100</b>——
      超了就是凭空多分钱，而那要等对账才发现。
    </p>
    <button :disabled="!schemeForm.reason.trim() || remainderSum > 100"
            @click="saveScheme(null)">保存为平台默认</button>
  </div>

  <div class="card">
    <h3>服务站联合</h3>
    <p class="hint">
      两个服务站互相引流。<b>发起方从自己的服务站佣金里切一部分给对方</b>，
      不额外增加总额；需要对方站长确认才生效。
    </p>
    <div v-if="!stations.length" class="empty">还没有已审核的服务站</div>
    <table v-else>
      <thead><tr><th>服务站</th><th style="width:110px">编号</th><th style="width:110px"></th></tr></thead>
      <tbody>
        <template v-for="s in stations" :key="s.orgId">
          <tr>
            <td>
              {{ s.name }}
              <span v-if="defaultStationId === s.orgId" class="tag ok" style="margin-left:6px">平台默认</span>
            </td>
            <td>#{{ s.orgId }}</td>
            <td>
              <div class="row" style="gap:6px">
                <button class="ghost sm" @click="toggleManage(s.orgId)">
                  {{ manageOpen === s.orgId ? '收起' : '管理' }}</button>
                <button class="ghost sm" @click="toggleJoints(s.orgId)">
                  {{ jointOpen === s.orgId ? '收起' : '联合关系' }}</button>
              </div>
            </td>
          </tr>
          <tr v-if="manageOpen === s.orgId">
            <td colspan="3" style="background:var(--surface-2);padding:12px">
              <div class="row" style="align-items:flex-end;gap:8px;margin-bottom:14px">
                <div class="field" style="flex:0 0 170px">
                  <label>站长（用户编号）</label>
                  <input v-model="masterOf[s.orgId]"
                         :placeholder="s.legalRepUserId ? `当前 #${s.legalRepUserId}` : '当前无站长'" />
                </div>
                <div class="field"><label>变更原因<span style="color:var(--bad)">*</span></label>
                  <input v-model="masterReason[s.orgId]" placeholder="必填，事后查得到是谁换的" /></div>
                <button :disabled="!masterOf[s.orgId] || busy === `master-${s.orgId}`"
                        @click="assignMaster(s.orgId)">指派</button>
                <button v-if="s.legalRepUserId" class="ghost"
                        :disabled="busy === `master-${s.orgId}`"
                        @click="assignMaster(s.orgId, true)">撤下站长</button>
              </div>
              <p class="hint" style="margin-top:0">
                换站长会改变<b>谁能设分成比例、谁能签联合协议</b>，所以要填原因。
              </p>

              <h4 style="margin:14px 0 6px">本站分成比例</h4>
              <div v-if="!ratesOf[s.orgId]?.length" class="hint" style="margin:0">
                没有单独设过，跟随平台默认
              </div>
              <table v-else>
                <thead><tr><th style="width:120px">类目</th><th style="width:100px">比例</th>
                           <th>更新时间</th></tr></thead>
                <tbody>
                  <tr v-for="r in ratesOf[s.orgId]" :key="r.category">
                    <td>{{ zhCategory(r.category) }}</td>
                    <td>{{ r.percent }}%</td>
                    <td style="color:var(--muted);font-size:12.5px">{{ when(r.updatedAt) }}</td>
                  </tr>
                </tbody>
              </table>
              <div class="row" style="align-items:flex-end;gap:8px;margin-top:8px">
                <div class="field" style="flex:0 0 130px"><label>类目</label>
                  <select v-model="rateCat">
                    <option v-for="c in CATEGORIES" :key="c.v" :value="c.v">{{ c.label }}</option>
                  </select>
                </div>
                <div class="field" style="flex:0 0 110px"><label>比例（%）</label>
                  <input v-model="ratePct" /></div>
                <div class="field"><label>调整原因<span style="color:var(--bad)">*</span></label>
                  <input v-model="rateReason" /></div>
                <button :disabled="!ratePct || !rateReason.trim()" @click="setRate(s.orgId)">设置</button>
              </div>

              <h4 style="margin:16px 0 6px">本站分配方案（按类目）</h4>
              <div v-if="!schemesOf[s.orgId]?.length" class="hint" style="margin:0">
                没有单独设过，跟随平台默认
              </div>
              <table v-else>
                <thead><tr><th style="width:90px">类目</th><th style="width:70px">主动</th>
                           <th style="width:70px">平台</th><th style="width:70px">被动</th>
                           <th style="width:80px">服务站</th><th style="width:70px">逐级</th>
                           <th>下限</th></tr></thead>
                <tbody>
                  <tr v-for="x in schemesOf[s.orgId]" :key="x.category">
                    <td>{{ zhCategory(x.category) }}</td>
                    <td>{{ x.activePct }}%</td><td>{{ x.platformPct }}%</td>
                    <td>{{ x.passivePct }}%</td><td>{{ x.stationPct }}%</td>
                    <td>{{ x.passiveStepPct }}%</td><td>{{ yuan(x.minPayoutCents) }}</td>
                  </tr>
                </tbody>
              </table>
              <button class="ghost sm" style="margin-top:6px"
                      :disabled="!schemeForm.reason.trim() || remainderSum > 100"
                      @click="saveScheme(s.orgId)">
                用上面「平台默认」那一组数字，设为本站方案
              </button>

              <h4 style="margin:16px 0 6px">与用工单位的合作</h4>
              <p class="hint" style="margin-top:0">
                <b>对方确认后才生效</b>；生效后才能指派操作员。
                服务站之间请走「联合」，不是「合作」。
              </p>
              <div v-if="!coopsOf[s.orgId]?.length" class="empty" style="padding:6px 0">
                还没有合作关系
              </div>
              <table v-else>
                <thead><tr><th style="width:70px">编号</th><th style="width:100px">用工单位</th>
                           <th style="width:90px">发起方</th><th style="width:90px">状态</th>
                           <th style="width:230px"></th></tr></thead>
                <tbody>
                  <template v-for="c in coopsOf[s.orgId]" :key="c.id">
                    <tr>
                      <td>#{{ c.id }}</td>
                      <td>#{{ c.partnerOrgId }}</td>
                      <td>{{ c.initiatedByStation ? '我方' : '对方' }}</td>
                      <td><span class="tag" :class="statusTone(c.status)">{{ zhStatus(c.status) }}</span></td>
                      <td>
                        <div class="row" style="gap:6px">
                          <button v-if="c.status === 'PENDING' && !c.initiatedByStation" class="sm"
                                  :disabled="busy === `c-${c.id}`"
                                  @click="coopAct(s.orgId, c.id, 'confirm', '已确认')">确认</button>
                          <button v-if="c.status === 'PENDING' && c.initiatedByStation" class="ghost sm"
                                  :disabled="busy === `c-${c.id}`"
                                  @click="coopAct(s.orgId, c.id, 'cancel', '已撤回')">撤回</button>
                          <button v-if="c.status === 'ACTIVE'" class="ghost sm"
                                  :disabled="busy === `c-${c.id}`"
                                  @click="coopAct(s.orgId, c.id, 'end', '已解除')">解除</button>
                          <button v-if="c.status === 'ACTIVE'" class="ghost sm"
                                  @click="loadOperators(c.id)">操作员</button>
                        </div>
                      </td>
                    </tr>
                    <tr v-if="operatorsOf[c.id]">
                      <td colspan="5" style="background:var(--surface);padding:10px">
                        <div v-if="!operatorsOf[c.id].length" class="hint" style="margin:0">
                          这份合作还没有操作员
                        </div>
                        <div v-for="o in operatorsOf[c.id]" :key="o.id"
                             class="row" style="gap:8px;padding:2px 0">
                          <span>用户 #{{ o.userId }}</span>
                          <button class="ghost sm" @click="revokeOperator(c.id, o.userId)">解绑</button>
                        </div>
                        <div class="row" style="align-items:flex-end;gap:8px;margin-top:8px">
                          <div class="field" style="flex:0 0 170px"><label>用户编号</label>
                            <input v-model="coopOperator[c.id]" placeholder="要实名认证过" /></div>
                          <button :disabled="!coopOperator[c.id] || busy === `op-${c.id}`"
                                  @click="assignOperator(c.id)">指派操作员</button>
                        </div>
                        <p class="hint" style="margin-bottom:0">
                          操作员挂在<b>这份合作</b>上，不是挂在服务站上——
                          和不同企业合作可以派不同的人。<b>解除合作会连带解绑</b>。
                        </p>
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
              <div class="row" style="align-items:flex-end;gap:8px;margin-top:10px">
                <div class="field" style="flex:0 0 190px"><label>用工单位编号</label>
                  <input v-model="coopPartner" placeholder="企业或工厂的组织编号" /></div>
                <button :disabled="!coopPartner || busy === `coop-${s.orgId}`"
                        @click="applyCoop(s.orgId)">发起合作</button>
              </div>

              <h4 style="margin:16px 0 6px">授权业务员</h4>
              <p class="hint" style="margin-top:0">
                站长可以直接把某人设为本站业务员。
                另一条路是<b>员工分享岗位/商品，对方成交后自动升级</b>。
              </p>
              <div class="row" style="align-items:flex-end;gap:8px">
                <div class="field" style="flex:0 0 180px"><label>用户编号</label>
                  <input v-model="grantUser[s.orgId]" placeholder="要实名认证过" /></div>
                <button :disabled="!grantUser[s.orgId] || busy === `grant-${s.orgId}`"
                        @click="grantBroker(s.orgId)">授权为业务员</button>
              </div>
            </td>
          </tr>

          <tr v-if="jointOpen === s.orgId">
            <td colspan="3" style="background:var(--surface-2);padding:12px">
              <div v-if="!jointsOf[s.orgId]?.length" class="empty" style="padding:6px 0">
                还没有联合关系
              </div>
              <table v-else>
                <thead><tr><th style="width:70px">编号</th><th style="width:100px">方向</th>
                           <th style="width:90px">对方</th><th style="width:90px">分成</th>
                           <th style="width:90px">状态</th><th style="width:200px"></th></tr></thead>
                <tbody>
                  <tr v-for="j in jointsOf[s.orgId]" :key="j.id">
                    <td>#{{ j.id }}</td>
                    <td>{{ j.fromOrgId === s.orgId ? '我方发起' : '对方发起' }}</td>
                    <td>#{{ j.fromOrgId === s.orgId ? j.toOrgId : j.fromOrgId }}</td>
                    <td>{{ j.ratePercent }}%</td>
                    <td><span class="tag" :class="statusTone(j.status)">{{ zhStatus(j.status) }}</span></td>
                    <td>
                      <div class="row" style="gap:6px">
                        <button v-if="j.status === 'PENDING' && j.toOrgId === s.orgId" class="sm"
                                :disabled="busy === `j-${j.id}`"
                                @click="jointAct(s.orgId, j.id, 'confirm', '已确认')">确认联合</button>
                        <button v-if="j.status === 'PENDING' && j.fromOrgId === s.orgId" class="ghost sm"
                                :disabled="busy === `j-${j.id}`"
                                @click="jointAct(s.orgId, j.id, 'cancel', '已撤回')">撤回</button>
                        <button v-if="j.status === 'ACTIVE'" class="ghost sm"
                                :disabled="busy === `j-${j.id}`"
                                @click="jointAct(s.orgId, j.id, 'end', '已解除')">解除联合</button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>

              <div class="row" style="align-items:flex-end;gap:8px;margin-top:12px">
                <div class="field" style="flex:0 0 180px"><label>联合方服务站编号</label>
                  <input v-model="jointTo" placeholder="对方的组织编号" /></div>
                <div class="field" style="flex:0 0 140px"><label>分给对方（%）</label>
                  <input v-model="jointRate" /></div>
                <button :disabled="!jointTo || busy === `joint-${s.orgId}`"
                        @click="applyJoint(s.orgId)">发起联合</button>
              </div>
              <p class="hint" style="margin-bottom:0">
                比例填 1–99。<b>解除后不会删记录</b>——历史佣金要靠它解释当初为什么分给了那个站。
              </p>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>


  <div class="card">
    <h3>设立服务站</h3>
    <p class="hint">
      服务站是平台自己的经营网点，由平台统一设立。
      <b>建出来时还没有站长</b>——先有点位，再决定派谁去管。
    </p>
    <div class="row">
      <div class="field" style="flex:0 0 160px"><label>主体类型</label>
        <select v-model="newSubject">
          <option value="COMPANY">公司</option>
          <option value="INDIVIDUAL">个人</option>
        </select>
      </div>
      <div class="field"><label>服务站名称</label>
        <input v-model="newName" placeholder="如：郑州高新区服务站" /></div>

      <div class="field" v-if="newSubject === 'COMPANY'"><label>统一社会信用代码</label>
        <input v-model="newCode" placeholder="收佣金要开对公账户，必填" /></div>

      <div class="field" v-else><label>个人主体（用户编号）</label>
        <input v-model="newPerson" placeholder="要实名认证过" /></div>
    </div>
    <div class="row">
      <div class="field"><label>地址（选填）</label>
        <input v-model="newAddress" placeholder="求职端岗位卡片上显示的地址" /></div>
      <button style="align-self:flex-end"
              :disabled="!newName.trim() || busy === 'create'
                         || (newSubject === 'COMPANY' ? !newCode.trim() : !newPerson)"
              @click="createStation">设立</button>
    </div>
    <p class="hint" v-if="newSubject === 'INDIVIDUAL'" style="margin-bottom:0">
      个人主体<b>没有统一社会信用代码</b>，所以不收这一项。
      和公司站不同的是，<b>个人站必须当场指定是谁</b>——"个人主体"指的就是那个人，
      没有人的个人服务站不知道在说谁。<b>一个人只能有一个个人服务站。</b>
    </p>
  </div>

  <div class="card note">
    <h3>平台默认服务站</h3>
    <p class="hint" style="margin-bottom:0">
      员工分享带来成交后自动升级为业务员，并<b>自动挂到把他带进来的那个业务员下面</b>，
      服务站随之继承——这样被动佣金才能往上分。
      <span v-if="defaultStationId > 0">树上继承不到时归<b>服务站 #{{ defaultStationId }}</b>（参数设置里可改）。</span>
      <span v-else>树上继承不到时<b>自动分配给当前业务员最少的站</b>。</span>
      不归站的业务员在分账时那一档不会分成，钱留在池子里，要等对账才发现。
    </p>
  </div>

  <div class="card">
    <h3>平台默认分成比例</h3>
    <p class="hint">
      对所有没单独设过的服务站生效。<b>岗位、商品、培训的毛利结构不同</b>，
      用同一个比例要么服务站在商品上亏、要么平台在岗位上亏。
    </p>
    <div v-if="!defaultRates.length" class="empty" style="padding:8px 0">还没有设过默认比例</div>
    <table v-else style="margin-bottom:12px">
      <thead><tr><th style="width:120px">类目</th><th style="width:100px">比例</th><th>更新时间</th></tr></thead>
      <tbody>
        <tr v-for="r in defaultRates" :key="r.category">
          <td>{{ zhCategory(r.category) }}</td>
          <td>{{ r.percent }}%</td>
          <td style="color:var(--muted);font-size:12.5px">{{ when(r.updatedAt) }}</td>
        </tr>
      </tbody>
    </table>
    <div class="row" style="align-items:flex-end;gap:8px">
      <div class="field" style="flex:0 0 130px"><label>类目</label>
        <select v-model="rateCat">
          <option v-for="c in CATEGORIES" :key="c.v" :value="c.v">{{ c.label }}</option>
        </select>
      </div>
      <div class="field" style="flex:0 0 110px"><label>比例（%）</label><input v-model="ratePct" /></div>
      <div class="field"><label>调整原因<span style="color:var(--bad)">*</span></label>
        <input v-model="rateReason" placeholder="必填，事后查得到是谁改的" /></div>
      <button :disabled="!ratePct || !rateReason.trim()" @click="setRate(null)">设为默认</button>
    </div>
  </div>

</template>
