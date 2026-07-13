<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import EnterprisesPanel from './EnterprisesPanel.vue'
import WorkUnitsPanel from './WorkUnitsPanel.vue'

const auth = useAuthStore()
</script>

<template>
  <WorkUnitsPanel v-if="auth.isEnterprise()" />
  <EnterprisesPanel v-else />
</template>
