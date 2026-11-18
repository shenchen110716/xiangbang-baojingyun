<script setup lang="ts">
import { ref } from 'vue'
import { marked } from 'marked'
import PageCard from '@/components/PageCard.vue'
// 文档单一来源在仓库 docs/，构建时以 ?raw 内联进 bundle，页面不发请求。
import usageMd from '../../../../docs/open-api-usage-guide.md?raw'
import designMd from '../../../../docs/open-api-platform-design.md?raw'

const activeTab = ref('usage')
// 内容是仓库内静态文档（非用户输入），v-html 渲染无注入风险。
const usageHtml = marked.parse(usageMd, { async: false }) as string
const designHtml = marked.parse(designMd, { async: false }) as string
</script>

<template>
  <PageCard title="开放平台" hint="面向企业客户的 Open API：设计规范与接入说明（尚未上线）">
    <el-tabs v-model="activeTab" class="docs-tabs">
      <el-tab-pane label="API 接口使用说明" name="usage">
        <div class="md-body" v-html="usageHtml" />
      </el-tab-pane>
      <el-tab-pane label="完整实现设计方案" name="design">
        <div class="md-body" v-html="designHtml" />
      </el-tab-pane>
    </el-tabs>
  </PageCard>
</template>

<style scoped>
.docs-tabs {
  padding: 0 20px 20px;
}
.md-body {
  max-width: 920px;
  line-height: 1.75;
  color: var(--el-text-color-primary);
  font-size: 14px;
}
.md-body :deep(h1) {
  font-size: 22px;
  margin: 8px 0 16px;
}
.md-body :deep(h2) {
  font-size: 17px;
  margin: 28px 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.md-body :deep(h3) {
  font-size: 15px;
  margin: 20px 0 8px;
}
.md-body :deep(blockquote) {
  margin: 12px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  color: var(--el-text-color-regular);
}
.md-body :deep(blockquote p) {
  margin: 0;
}
.md-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 1px 5px;
}
.md-body :deep(pre) {
  background: #1f2430;
  color: #e6e9f0;
  border-radius: 8px;
  padding: 14px 16px;
  overflow-x: auto;
}
.md-body :deep(pre code) {
  background: none;
  padding: 0;
  color: inherit;
}
.md-body :deep(table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--el-border-color-lighter);
  padding: 7px 12px;
  text-align: left;
  vertical-align: top;
}
.md-body :deep(th) {
  background: var(--el-fill-color-light);
  font-weight: 600;
}
.md-body :deep(ul),
.md-body :deep(ol) {
  padding-left: 22px;
}
.md-body :deep(li) {
  margin: 4px 0;
}
</style>
