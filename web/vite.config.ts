import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物直接落进 Spring 的静态资源目录:一个服务、一个域名、没有跨域。
// 免费档只给一个 Web 服务,再起一个前端容器不划算,而且要处理 CORS 与两套部署。
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    // 本地开发时把 /api 转发到后端,免得开发态还要开 CORS
    proxy: { '/api': 'http://localhost:8080', '/actuator': 'http://localhost:8080' },
  },
})
