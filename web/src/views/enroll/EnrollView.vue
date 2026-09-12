<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  createPaymentOrder,
  fetchEnrollInfo,
  fetchPaymentStatus,
  submitEnroll,
  type EnrollInfo,
} from '@/api/enroll'

const route = useRoute()
const positionId = route.params.positionId as string
const mode = route.params.mode as string
const token = route.params.token as string

const info = ref<EnrollInfo | null>(null)
const unavailable = ref(false)
const loading = ref(true)
const errorText = ref('')
const submitting = ref(false)

// step: form → paying(仅个人缴纳) → done
const step = ref<'form' | 'paying' | 'done'>('form')
const submissionId = ref<number | null>(null)
const paymentStatus = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

const form = reactive({
  name: '',
  id_number: '',
  phone: '',
  website: '', // 蜜罐字段，真人看不到也不会填；见 .hp-field 样式和后端 routers/enroll.py
})

onMounted(async () => {
  try {
    info.value = await fetchEnrollInfo(positionId, mode, token)
  } catch {
    unavailable.value = true
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})

async function submit() {
  errorText.value = ''
  if (!form.name.trim() || !form.id_number.trim()) {
    errorText.value = '请填写姓名和身份证号'
    return
  }
  submitting.value = true
  try {
    const result = await submitEnroll(positionId, mode, token, form)
    submissionId.value = result.submission_id
    if (result.payment_mode === 'personal') {
      step.value = 'paying'
    } else {
      step.value = 'done'
    }
  } catch (e) {
    errorText.value = (e as Error).message
  } finally {
    submitting.value = false
  }
}

async function startPayment() {
  if (!submissionId.value) return
  errorText.value = ''
  submitting.value = true
  try {
    const order = await createPaymentOrder(submissionId.value)
    // 每 3 秒查一次支付状态；微信H5支付在新页面完成，用户切回来时这里已经变绿
    if (pollTimer) clearInterval(pollTimer)
    pollTimer = setInterval(async () => {
      if (!submissionId.value) return
      try {
        const status = await fetchPaymentStatus(submissionId.value)
        paymentStatus.value = status.payment_status
        if (status.payment_status === 'paid') {
          if (pollTimer) clearInterval(pollTimer)
          step.value = 'done'
        }
      } catch {
        /* 网络抖动忽略，下一轮再查 */
      }
    }, 3000)
    window.location.href = order.mweb_url
  } catch (e) {
    errorText.value = (e as Error).message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="enroll-screen">
    <div class="enroll-brand">
      <span class="brand-mark">响</span>
      <span class="brand-text">响帮帮<span class="brand-sub">XIANGBANGBANG · 无忧保</span></span>
    </div>

    <div class="enroll-card" v-if="loading">
      <p class="enroll-lede">加载中…</p>
    </div>

    <div class="enroll-card" v-else-if="unavailable">
      <h1>该岗位当前不可参保</h1>
      <p class="enroll-lede">二维码可能已失效或岗位暂未开放，请联系你的单位HR获取最新二维码。</p>
    </div>

    <div class="enroll-card" v-else-if="step === 'form'">
      <h1>{{ info?.position_name }}</h1>
      <p class="enroll-lede">
        {{ info?.actual_employer }}
        <span class="mode-tag">{{ mode === 'personal' ? '个人缴纳' : '单位缴纳' }}</span>
      </p>
      <div class="plan-line" v-if="info?.plan_name">
        <span>参保方案：{{ info.plan_name }}</span>
        <span v-if="mode === 'personal' && info.plan_price != null" class="plan-price">￥{{ info.plan_price }}</span>
      </div>

      <el-form label-position="top" @submit.prevent>
        <div class="hp-field" aria-hidden="true">
          <label for="enroll-website">Website</label>
          <input id="enroll-website" v-model="form.website" type="text" tabindex="-1" autocomplete="off" />
        </div>
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="与身份证一致" />
        </el-form-item>
        <el-form-item label="身份证号" required>
          <el-input v-model="form.id_number" placeholder="18位身份证号" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="选填" />
        </el-form-item>
      </el-form>

      <p v-if="errorText" class="enroll-error">{{ errorText }}</p>
      <el-button type="primary" size="large" :loading="submitting" style="width: 100%" @click="submit">
        {{ mode === 'personal' ? '提交并去支付' : '提交' }}
      </el-button>
      <p class="enroll-note">提交后信息将报送平台审核，审核通过后正式参保。</p>
    </div>

    <div class="enroll-card" v-else-if="step === 'paying'">
      <h1>信息已提交</h1>
      <p class="enroll-lede">请完成支付，支付成功后进入平台审核。</p>
      <div class="plan-line" v-if="info?.plan_price != null">
        <span>应付金额</span>
        <span class="plan-price">￥{{ info.plan_price }}</span>
      </div>
      <p v-if="errorText" class="enroll-error">{{ errorText }}</p>
      <el-button type="primary" size="large" :loading="submitting" style="width: 100%" @click="startPayment">
        微信支付
      </el-button>
      <p class="enroll-note">支付完成后返回本页，状态会自动更新。</p>
    </div>

    <div class="enroll-card enroll-success" v-else>
      <h1>提交成功</h1>
      <p class="enroll-lede" v-if="mode === 'personal'">支付已完成，请等待平台审核，审核通过后正式参保。</p>
      <p class="enroll-lede" v-else>请等待平台审核，审核通过后正式参保。</p>
    </div>
  </div>
</template>

<style scoped>
.enroll-screen {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 16px;
  background: var(--el-bg-color-page, #f5f6f8);
}
.enroll-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 28px;
}
.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #1f2a44;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.brand-text { font-weight: 700; }
.brand-sub {
  display: block;
  font-size: 11px;
  font-weight: 400;
  opacity: 0.6;
  letter-spacing: 0.04em;
}
.enroll-card {
  width: 100%;
  max-width: 440px;
  background: var(--el-bg-color, #fff);
  border-radius: 16px;
  padding: 32px 28px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
}
.enroll-card h1 { font-size: 20px; margin-bottom: 8px; }
.enroll-lede { font-size: 13.5px; color: var(--el-text-color-secondary); margin-bottom: 20px; }
.mode-tag {
  display: inline-block;
  margin-left: 8px;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}
.plan-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--el-fill-color-light, #f5f7fa);
  font-size: 13.5px;
  margin-bottom: 20px;
}
.plan-price { font-weight: 700; color: var(--el-color-danger, #f56c6c); }
.enroll-error { color: var(--el-color-danger); font-size: 13px; margin-bottom: 12px; }
.enroll-note { margin-top: 14px; font-size: 12px; color: var(--el-text-color-secondary); text-align: center; }
.hp-field { position: absolute; left: -9999px; top: -9999px; width: 1px; height: 1px; overflow: hidden; }
.enroll-success { text-align: center; }
</style>
