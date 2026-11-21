import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 后端网关默认 8080；可用 VITE_API_BASE 覆盖（例如直连服务 8081/8082/8083）
const API_BASE = process.env.VITE_API_BASE || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      '/api': { target: API_BASE, changeOrigin: true },
      // 监控探活代理：避免浏览器直接跨域请求 3000/9090/9093 被 CORS 拦截误报未启动
      '/_probe/grafana': {
        target: 'http://localhost:3000',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/_probe\/grafana/, ''),
      },
      '/_probe/prometheus': {
        target: 'http://localhost:9090',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/_probe\/prometheus/, ''),
      },
      '/_probe/alertmanager': {
        target: 'http://localhost:9093',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/_probe\/alertmanager/, ''),
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus', '@element-plus/icons-vue'],
        },
      },
    },
  },
})
