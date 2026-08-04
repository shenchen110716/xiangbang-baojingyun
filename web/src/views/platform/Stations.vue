<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, when } from '../../api'

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
onMounted(load)
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
</template>
