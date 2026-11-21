# Mall Coupon Platform 管理台（前端）

基于 **Vue 3 + TypeScript + Vite + Element Plus** 的运营管理台，对接后端 API 网关。

## 1. 启动

> Windows 用户请在 **原生 PowerShell** 下执行（不要在 WSL 里访问 `/mnt/d/...` 跑 `npm`/`vite`，避免跨 9P 文件系统造成极高磁盘 IO）。

```powershell
cd web
npm install
npm run dev        # http://localhost:5173
```

前提：后端已启动（网关 :8080 + coupon 8081 / seckill 8082 / order 8083）：

```powershell
# Windows PowerShell
.\scripts\mall.ps1 start

# Linux / macOS
./scripts/mall.sh start --profile dev
```

> 演示登录走网关白名单 `/api/auth/token`（仅 dev/local profile 启用），输入任意用户 ID + 角色即可签发 JWT；生产环境需替换为真实登录。

## 2. 功能

| 页面 | 能力 |
| --- | --- |
| 总览 | 券模板/我的券/活动统计、快速开始引导、监控入口 |
| 券模板管理 | 列表/搜索/筛选、新建、发布、领券、详情 |
| 我的优惠券 | 按状态筛选、异步落库刷新、核销 |
| 秒杀活动 | 列表、新建、发布（库存预热）、跳转抢购 |
| 秒杀抢购 | 下单返回 orderId、轮询 `/api/seckill/result` 获取异步落单结果 |
| 订单查询 | 按订单号查询、支付、取消（归还库存） |
| 监控中心 | 探测 Grafana/Prometheus/Alertmanager 可达性并提供直达链接 |

## 3. 构建与部署

```bash
npm run build       # 产物在 web/dist/
npm run preview     # 本地预览构建产物
```

`dist/` 是纯静态文件，可部署到任意 nginx / CDN / 对象存储。Vite dev 模式已配 `/api` 代理到网关；生产部署时把 `dist/` 放 nginx 并配置反向代理：

```nginx
location /api/ { proxy_pass http://gateway:8080; }
```

## 4. 环境变量

- `VITE_API_BASE`：dev 模式代理目标，默认 `http://localhost:8080`（网关）。若网关未启动可直连服务，但前端路由 `/api/coupon`、`/api/seckill`、`/api/orders` 分属不同端口，需要多个代理——建议始终走网关。

## 5. 说明

- 所有业务响应遵循后端 `R<T>`（`code=0` 成功），`src/api/request.ts` 统一处理业务码和 401 跳登录。
- JWT 存在 localStorage（`mall_admin_token`），请求拦截器自动加 `Authorization: Bearer <token>`。
- 抢购是异步的：下单接口只返回 orderId，结果通过轮询获取；订单落库还有秒级延迟，属预期最终一致性。
