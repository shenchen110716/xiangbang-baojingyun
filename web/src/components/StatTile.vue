<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  hint?: string
  hintType?: 'success' | 'warning' | 'danger' | 'info'
  // 可选：Element Plus 图标组件名（已全局注册），传入则右上角显示彩色图标片
  icon?: string
  // 可选：图标片与强调条配色，默认主色蓝
  accent?: 'primary' | 'success' | 'warning' | 'danger' | 'info'
}>()
</script>

<template>
  <div class="stat-tile" :class="[`accent-${accent || hintType || 'primary'}`]">
    <div class="stat-top">
      <div class="stat-label">{{ label }}</div>
      <span v-if="icon" class="stat-icon">
        <el-icon><component :is="icon" /></el-icon>
      </span>
    </div>
    <b class="stat-value tabular">{{ value }}</b>
    <small v-if="hint" :class="['stat-hint', hintType]">
      <i class="dot" />{{ hint }}
    </small>
  </div>
</template>

<style scoped>
.stat-tile {
  position: relative;
  background: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--app-radius-card);
  padding: 16px 18px 15px;
  min-height: 104px;
  box-shadow: var(--app-shadow-sm);
  transition: box-shadow 0.18s ease, transform 0.18s ease, border-color 0.18s ease;
  overflow: hidden;
}
/* 左侧强调条，按 accent 着色 */
.stat-tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 14px;
  bottom: 14px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--accent-color);
  opacity: 0.85;
}
.stat-tile:hover {
  box-shadow: var(--app-shadow-hover);
  transform: translateY(-2px);
  border-color: var(--el-border-color-light);
}
.stat-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.stat-label {
  color: var(--el-text-color-secondary);
  font-size: var(--app-fs-caption);
  font-weight: 500;
  letter-spacing: 0.01em;
}
.stat-icon {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  font-size: 16px;
  color: var(--accent-color);
  background: var(--accent-soft);
  flex: none;
}
.stat-value {
  display: block;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--el-text-color-primary);
  margin: 12px 0 7px;
  letter-spacing: -0.02em;
}
.stat-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--app-fs-micro);
  color: var(--el-text-color-secondary);
}
.stat-hint .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.55;
}
.stat-hint.success {
  color: var(--el-color-success);
}
.stat-hint.warning {
  color: var(--el-color-warning);
}
.stat-hint.danger {
  color: var(--el-color-danger);
}
.stat-hint.info {
  color: var(--el-color-primary);
}

/* accent 主题：驱动强调条与图标片配色 */
.accent-primary {
  --accent-color: var(--el-color-primary);
  --accent-soft: var(--el-color-primary-light-9);
}
.accent-success {
  --accent-color: var(--el-color-success);
  --accent-soft: var(--el-color-success-light-9);
}
.accent-warning {
  --accent-color: var(--el-color-warning);
  --accent-soft: var(--el-color-warning-light-9);
}
.accent-danger {
  --accent-color: var(--el-color-danger);
  --accent-soft: var(--el-color-danger-light-9);
}
.accent-info {
  --accent-color: var(--el-color-primary);
  --accent-soft: var(--el-color-primary-light-9);
}
</style>
