# 响帮帮保经云

可运行的保险经纪公司管理系统 v3.0（三端统一架构）。

## 启动

```bash
python3 -m pip install -r requirements.txt
python3 -m uvicorn backend.app:app --host 127.0.0.1 --port 8001 --reload
```

打开 http://127.0.0.1:8001/

默认管理员：`admin` / `admin123`（生产环境请设置 `ADMIN_PASSWORD` 和 `JWT_SECRET`）。
默认参保单位账号：`enterprise` / `enterprise123`（生产环境请设置 `ENTERPRISE_PASSWORD`）。

## 云端部署

项目已包含 `Dockerfile` 和 `render.yaml`，可在 Render Blueprint 中一次创建 Web Service 和 PostgreSQL：

1. 将源码推送到私有 GitHub 仓库，不要上传 `.env` 或本地 `data.db`。
2. 在 Render 选择 **New > Blueprint**，连接该仓库并应用根目录下的 `render.yaml`。
3. 部署完成后，在服务的 Environment 中查看自动生成的 `ADMIN_PASSWORD` 和 `ENTERPRISE_PASSWORD`。
4. 打开 Render 分配的 HTTPS 域名；`/api/health` 返回 `ok: true` 即表示发布成功。

云端默认使用 PostgreSQL 保存业务数据。理赔附件目录需在长期运营前接入持久化磁盘或对象存储。

## API

- `POST /api/auth/login` 登录并获得 JWT
- `GET /api/auth/me` 当前用户
- `GET /api/dashboard` 工作台指标
- `GET/POST /api/enterprises` 合作企业
- `GET/POST /api/plans` 保险方案
- `GET/POST /api/insured` 投保人员
- `GET/POST /api/claims` 工伤理赔
- `GET /api/providers/status` 外部服务配置状态
- `POST /api/enrollment/send` 保司参停保发送
- `POST /api/notifications/send` 邮件/短信通知
- `POST /api/payments` 支付下单
- `POST /api/payments/callback` 支付回调入账
- `GET /api/payments/reconcile` 充值对账
- `GET/POST/PATCH /api/claims/{id}/documents` 理赔材料流转
- `GET /docs` OpenAPI 交互文档

## 真实服务接入

复制 `.env.example` 为 `.env`，填写环境变量后切换 `INTEGRATION_MODE=real`。保司 API、短信、邮件、支付服务通过 Provider 适配层接入；当前默认 `mock`，不会向第三方发送真实请求，也不存储任何密钥。

## 微信小程序

使用微信开发者工具打开 `miniprogram/` 目录，将 `app.js` 的 `apiBase` 改为部署后的 HTTPS API 地址，并替换 `project.config.json` 中的正式 AppID。小程序包含员工、批量导入、参停保、产品、资金、理赔材料页面；企业管理员可使用 `onShareAppMessage` 分享安全业务链接。
