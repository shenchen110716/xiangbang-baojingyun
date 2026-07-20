<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { ElMessage } from 'element-plus'
import { createPayment, getPaymentStatus } from '@/api/payments'

const props = defineProps<{ enterpriseId: number; amount: number }>()
const emit = defineEmits<{ paid: []; cancel: [] }>()

const loading = ref(false)
const orderNo = ref('')
const codeUrl = ref('')
const canvasRef = ref<HTMLCanvasElement | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function start() {
  stopPolling()
  if (!props.enterpriseId || props.amount <= 0) {
    ElMessage.error('请先选择投保单位并输入充值金额')
    return
  }
  loading.value = true
  try {
    const result = await createPayment({ enterprise_id: props.enterpriseId, account: 'usage', amount: props.amount, channel: 'native' })
    orderNo.value = result.order_no
    codeUrl.value = result.code_url || ''
    if (canvasRef.value && codeUrl.value) await QRCode.toCanvas(canvasRef.value, codeUrl.value, { width: 200 })
    pollTimer = setInterval(async () => {
      try {
        const status = await getPaymentStatus(orderNo.value)
        if (status.status === 'paid') {
          stopPolling()
          ElMessage.success('支付成功')
          emit('paid')
        }
      } catch {
        /* 轮询失败静默重试，直到用户取消或下次成功 */
      }
    }, 2000)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

function cancel() {
  stopPolling()
  emit('cancel')
}

watch(() => [props.enterpriseId, props.amount], () => {
  stopPolling()
  orderNo.value = ''
  codeUrl.value = ''
})

onBeforeUnmount(stopPolling)

defineExpose({ start })
</script>

<template>
  <div class="wechat-pay-panel">
    <el-button v-if="!orderNo" type="primary" :loading="loading" @click="start">生成收款二维码</el-button>
    <div v-else class="qr-area">
      <canvas ref="canvasRef" />
      <p class="muted">请使用微信扫码支付 ¥{{ amount.toFixed(2) }}，支付成功后页面会自动刷新</p>
      <el-button size="small" @click="cancel">取消</el-button>
    </div>
  </div>
</template>

<style scoped>
.wechat-pay-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
}
.qr-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.muted {
  color: var(--el-text-color-placeholder);
  font-size: 12.5px;
  text-align: center;
}
</style>
