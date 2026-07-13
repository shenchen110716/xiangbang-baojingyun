<script setup lang="ts">
import { computed } from 'vue'

// Replaces the legacy .status.on/.wait/.off/.done inline-colored labels.
const props = defineProps<{
  status: string
  textMap?: Record<string, string>
}>()

const TYPE_MAP: Record<string, 'success' | 'warning' | 'info' | 'danger'> = {
  active: 'success',
  approved: 'success',
  issued: 'success',
  paid: 'success',
  accepted: 'success',
  on: 'success',
  pending: 'warning',
  supplement: 'warning',
  wait: 'warning',
  insurer_review: 'warning',
  submitted: 'warning',
  collecting: 'warning',
  reported: 'warning',
  stopped: 'info',
  closed: 'info',
  paused: 'info',
  off: 'info',
  inactive: 'info',
  rejected: 'danger',
}

const type = computed(() => TYPE_MAP[props.status] || 'info')
const label = computed(() => props.textMap?.[props.status] || props.status)
</script>

<template>
  <el-tag :type="type" size="small" disable-transitions>{{ label }}</el-tag>
</template>
