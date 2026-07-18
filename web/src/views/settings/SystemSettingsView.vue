<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageCard from '@/components/PageCard.vue'
import * as settingsApi from '@/api/settings'
import type { SettingGroup } from '@/api/settings'

const loading = ref(true)
const saving = ref(false)
const groups = ref<SettingGroup[]>([])
// 表单模型：非密钥项存当前值，bool 存 '1'/'0'，密钥项存用户新输入（留空=不改动）
const model = reactive<Record<string, string>>({})

function hydrate(gs: SettingGroup[]) {
  groups.value = gs
  for (const g of gs) {
    for (const it of g.items) {
      model[it.key] = it.secret ? '' : it.value
    }
  }
}

async function load() {
  loading.value = true
  try {
    hydrate(await settingsApi.getSystemSettings())
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function save() {
  saving.value = true
  try {
    const values: Record<string, string> = {}
    for (const g of groups.value) {
      for (const it of g.items) {
        if (it.secret) {
          // 密钥：仅在用户输入了新值时提交，留空表示保持原值不动
          if (model[it.key]) values[it.key] = model[it.key]
        } else {
          values[it.key] = model[it.key] ?? ''
        }
      }
    }
    hydrate(await settingsApi.updateSystemSettings(values))
    ElMessage.success('系统设置已保存')
  } catch (e) {
    ElMessage.error((e as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="system-settings">
    <el-alert type="info" :closable="false" show-icon class="intro">
      平台端运营配置集中管理。密钥类信息（如 Secret Access Key、OCR AppKey）会加密后存储，
      页面只显示是否已配置，不回显明文；留空表示保持原值不变。数据库未配置的项自动回落到服务器环境变量。
    </el-alert>

    <PageCard v-for="g in groups" :key="g.group" :title="g.group">
      <div class="form-body">
        <div v-for="it in g.items" :key="it.key" class="field-row">
          <div class="field-label">
            <span>{{ it.label }}</span>
            <el-tag v-if="it.secret" :type="it.configured ? 'success' : 'info'" size="small" effect="light">
              {{ it.configured ? '已配置' : '未配置' }}
            </el-tag>
            <code class="field-key">{{ it.key }}</code>
          </div>
          <div class="field-control">
            <el-switch v-if="it.kind === 'bool'" v-model="model[it.key]" active-value="1" inactive-value="0" />
            <el-select v-else-if="it.kind === 'select'" v-model="model[it.key]" style="width: 100%">
              <el-option v-for="opt in it.options || []" :key="opt" :label="opt" :value="opt" />
            </el-select>
            <el-input
              v-else-if="it.kind === 'password'"
              v-model="model[it.key]"
              type="password"
              show-password
              :placeholder="it.configured ? '已配置，如需更换请输入新值' : '未配置'"
            />
            <el-input v-else v-model="model[it.key]" :placeholder="it.hint || ''" />
            <div v-if="it.hint" class="field-hint">{{ it.hint }}</div>
          </div>
        </div>
      </div>
    </PageCard>

    <div class="save-bar">
      <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
    </div>
  </div>
</template>

<style scoped>
.system-settings {
  display: grid;
  gap: 18px;
  max-width: 860px;
}
.intro {
  border-radius: var(--app-radius-card);
}
.form-body {
  padding: 8px 20px 18px;
  display: grid;
  gap: 18px;
}
.field-row {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 20px;
  align-items: start;
}
.field-label {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 6px;
  font-weight: 500;
  color: var(--el-text-color-regular);
}
.field-key {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  background: var(--el-fill-color-light);
  padding: 1px 6px;
  border-radius: 5px;
}
.field-control {
  min-width: 0;
}
.field-hint {
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
.save-bar {
  position: sticky;
  bottom: 0;
  padding: 14px 0;
  display: flex;
  justify-content: flex-end;
}
@media (max-width: 720px) {
  .field-row {
    grid-template-columns: 1fr;
    gap: 8px;
  }
}
</style>
