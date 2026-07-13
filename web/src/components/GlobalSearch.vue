<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { listClaims } from '@/api/claims'
import { listEnterprises } from '@/api/enterprises'
import { listInsured } from '@/api/insured'
import { listPolicies } from '@/api/reports'
import { listPositions } from '@/api/positions'

const visible = defineModel<boolean>({ default: false })
const router = useRouter()
const keyword = ref('')
const loading = ref(false)
const results = ref<Array<{ type: string; label: string; sub: string; go: () => void }>>([])

// Mirrors the legacy openGlobalSearch(): fans out to 5 unfiltered list
// endpoints and filters client-side (there's no dedicated /search backend
// endpoint — noted as a future backend follow-up, not blocking this migration).
async function search() {
  const q = keyword.value.trim().toLowerCase()
  if (!q) { results.value = []; return }
  loading.value = true
  try {
    const [claims, enterprises, insured, policies, positions] = await Promise.all([
      listClaims().catch(() => []),
      listEnterprises().catch(() => []),
      listInsured().catch(() => []),
      listPolicies().catch(() => []),
      listPositions().catch(() => []),
    ])
    const out: typeof results.value = []
    for (const c of claims) {
      if (`${c.claim_no}${c.person_name}${c.enterprise_name}`.toLowerCase().includes(q)) {
        out.push({ type: '理赔', label: c.claim_no, sub: `${c.person_name} · ${c.enterprise_name}`, go: () => { router.push({ name: 'claims' }); visible.value = false } })
      }
    }
    for (const e of enterprises) {
      if (e.name.toLowerCase().includes(q)) {
        out.push({ type: '投保单位', label: e.name, sub: e.contact || '—', go: () => { router.push({ name: 'team' }); visible.value = false } })
      }
    }
    for (const p of insured) {
      if (`${p.name}${p.phone}`.toLowerCase().includes(q)) {
        out.push({ type: '参保员工', label: p.name, sub: p.enterprise_name || '—', go: () => { router.push({ name: 'workers' }); visible.value = false } })
      }
    }
    for (const p of policies) {
      if (`${p.policy_no}${p.enterprise_name}`.toLowerCase().includes(q)) {
        out.push({ type: '保单', label: p.policy_no, sub: p.enterprise_name, go: () => { router.push({ name: 'policy' }); visible.value = false } })
      }
    }
    for (const p of positions) {
      if (p.name.toLowerCase().includes(q)) {
        out.push({ type: '岗位', label: p.name, sub: p.actual_employer_name || p.actual_employer || '—', go: () => { router.push({ name: 'dispatch' }); visible.value = false } })
      }
    }
    results.value = out.slice(0, 30)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="全局搜索" width="520px" append-to-body destroy-on-close>
    <el-input v-model="keyword" placeholder="搜索企业、员工、岗位、保单或理赔案件" clearable autofocus @keyup.enter="search" @clear="results = []">
      <template #suffix>
        <el-icon @click="search"><Search /></el-icon>
      </template>
    </el-input>
    <div v-loading="loading" class="results">
      <div v-for="(r, i) in results" :key="i" class="result-row" @click="r.go">
        <el-tag size="small">{{ r.type }}</el-tag>
        <div>
          <b>{{ r.label }}</b>
          <small>{{ r.sub }}</small>
        </div>
      </div>
      <el-empty v-if="!loading && keyword && results.length === 0" description="没有找到匹配结果" :image-size="60" />
    </div>
  </el-dialog>
</template>

<style scoped>
.results {
  margin-top: 16px;
  max-height: 360px;
  overflow: auto;
}
.result-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.result-row div {
  display: grid;
}
.result-row small {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}
</style>
