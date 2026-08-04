<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, yuan } from '../../api'
import { zhStatus, statusTone, zhPayType, hours } from '../../i18n'

const settlements = ref<any[]>([])
const payouts = ref<any[]>([])
const loading = ref(true)
const err = ref('')

/** 展开中的工资条。按结算单缓存,免得折叠再展开重拉。 */
const openSlip = ref<number | null>(null)
const slips = ref<Record<number, any>>({})
const slipErr = ref<Record<number, string>>({})

async function load() {
  loading.value = true; err.value = ''
  try {
    settlements.value = await api('/api/settlement/mine')
    payouts.value = await api('/api/fund/payouts/mine')
  } catch (e: any) { err.value = e.message }
  finally { loading.value = false }
}

async function toggleSlip(id: number) {
  if (openSlip.value === id) { openSlip.value = null; return }
  openSlip.value = id
  if (slips.value[id]) return
  delete slipErr.value[id]
  try { slips.value[id] = await api(`/api/settlement/${id}/payslip`) }
  catch (e: any) { slipErr.value[id] = e.message }
}

const total = () => payouts.value.filter(p => p.status === 'PAID')
  .reduce((s, p) => s + (p.amountCents || 0), 0)

onMounted(load)
</script>

<template>
  <h1>我的工资</h1>
  <p class="sub">履约完成后自动生成工资单，平台放款后进入发放记录。<b>点开工资单看这笔钱是怎么算的</b></p>

  <div v-if="err" class="msg bad">{{ err }}</div>

  <div class="card note">
    <p class="hint" style="margin:0">
      测试环境：代发通道是 mock，「已发放」不代表钱真的到账。
    </p>
  </div>

  <div class="card">
    <h3>累计已发放</h3>
    <div style="font-size:28px;font-weight:650;color:var(--primary);font-family:var(--mono);text-shadow:0 0 18px rgba(34,211,238,.35)">{{ yuan(total()) }}</div>
  </div>

  <div class="card">
    <h3>工资单</h3>
    <div v-if="loading" class="msg info">加载中…</div>
    <div v-else-if="!settlements.length" class="empty">还没有工资单</div>
    <table v-else>
      <thead><tr><th style="width:110px">结算单</th><th style="width:130px">金额</th>
                 <th style="width:100px">状态</th><th style="width:110px"></th></tr></thead>
      <tbody>
        <template v-for="s in settlements" :key="s.id">
          <tr>
            <td>#{{ s.id }}</td>
            <td style="font-family:var(--mono)">{{ yuan(s.amountCents) }}</td>
            <td><span class="tag" :class="statusTone(s.status)">{{ zhStatus(s.status) }}</span></td>
            <td><button class="ghost sm" @click="toggleSlip(s.id)">
              {{ openSlip === s.id ? '收起' : '工资条' }}</button></td>
          </tr>

          <tr v-if="openSlip === s.id">
            <td colspan="4" style="background:var(--surface-2);padding:14px">
              <div v-if="slipErr[s.id]" class="msg bad" style="margin:0">{{ slipErr[s.id] }}</div>
              <div v-else-if="!slips[s.id]" class="msg info" style="margin:0">加载中…</div>
              <template v-else>
                <div class="row" style="gap:28px;margin-bottom:12px">
                  <div>
                    <div class="hint" style="margin:0">计薪方案</div>
                    <div style="font-weight:550">
                      {{ slips[s.id].payPlanName || '未启用（按岗位一口价）' }}
                      <span v-if="slips[s.id].payType" style="color:var(--muted);font-weight:400">
                        · {{ zhPayType(slips[s.id].payType) }}
                      </span>
                    </div>
                  </div>
                  <div v-if="slips[s.id].payPlanId">
                    <div class="hint" style="margin:0">计薪工时</div>
                    <div style="font-weight:550">
                      {{ hours(slips[s.id].minutes) }} · {{ slips[s.id].workDays }} 天
                    </div>
                  </div>
                </div>

                <table>
                  <thead><tr><th>项目</th><th style="width:140px;text-align:right">金额</th></tr></thead>
                  <tbody>
                    <tr v-for="(l, i) in slips[s.id].lines" :key="i">
                      <td>{{ l.name }}</td>
                      <td style="text-align:right;font-family:var(--mono)"
                          :style="{ color: l.amountCents < 0 ? 'var(--bad,#f87171)' : undefined }">
                        {{ yuan(l.amountCents) }}
                      </td>
                    </tr>
                    <tr>
                      <td style="font-weight:600">应发合计</td>
                      <td style="text-align:right;font-weight:600;font-family:var(--mono)">
                        {{ yuan(slips[s.id].amountCents) }}
                      </td>
                    </tr>
                  </tbody>
                </table>

                <p v-if="slips[s.id].voidReason" class="msg bad" style="margin:10px 0 0">
                  这张工资单已作废：{{ slips[s.id].voidReason }}
                </p>
                <p class="hint" style="margin:10px 0 0">
                  明细逐行加起来等于应发合计。<b>对不上就找工厂核考勤</b>，
                  「我的考勤」里能看到每天记了多少工时。
                </p>
              </template>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>

  <div class="card">
    <h3>发放记录</h3>
    <div v-if="!payouts.length && !loading" class="empty">还没有发放记录</div>
    <table v-else-if="payouts.length">
      <thead><tr><th>发放单</th><th>对应结算</th><th>金额</th><th>状态</th></tr></thead>
      <tbody>
        <tr v-for="p in payouts" :key="p.id">
          <td>#{{ p.id }}</td><td>#{{ p.settlementId }}</td>
          <td style="font-family:var(--mono)">{{ yuan(p.amountCents) }}</td>
          <td><span class="tag" :class="statusTone(p.status)">{{ zhStatus(p.status) }}</span></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
