# 响帮帮无忧保

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

项目已包含 `Dockerfile` 和 `render.yaml`，测试环境推荐使用 Render 免费 Web Service + Neon 免费 PostgreSQL：

1. 将源码推送到私有 GitHub 仓库，不要上传 `.env`、本地 `data.db` 或 `uploads/`。
2. 在 Neon 创建 PostgreSQL 项目，复制 Direct（非 pooled）连接字符串。当前容器启动时会运行 Alembic，因此迁移连接应使用 Direct 地址。
3. 在 Render 选择 **New > Blueprint**，连接该仓库并应用根目录下的 `render.yaml`。
4. 按提示输入 Neon `DATABASE_URL`、强 `ADMIN_PASSWORD` 和强 `ENTERPRISE_PASSWORD`；这些值只存储在 Render 密钥配置中。
5. 打开 Render 分配的 HTTPS 域名；`/api/health` 返回 `ok: true` 即表示发布成功。

云端使用 PostgreSQL 保存业务数据。Render 免费 Web Service 的本地文件系统不持久化，理赔附件和岗位视频只适合演示；长期运营前必须接入私有对象存储。

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

使用微信开发者工具打开 `miniprogram/` 目录。默认 API 为 `http://127.0.0.1:8001/api`，开发者工具中可直接连接本地后端；真机调试或正式发布时，在小程序“我的 → 系统设置”中切换为部署后的 HTTPS API 地址，并替换 `project.config.json` 中的正式 AppID。

用户端完整功能包括：

- 企业账号登录、JWT 续期校验、租户数据隔离和退出登录。
- 首页指标、余额预警、待办消息和快捷办理。
- 实际用工单位、投保岗位、岗位视频上传和职业定类进度。
- 员工搜索筛选、新增编辑、详情、参保、停保、恢复和 CSV/XLSX 批量导入。
- 保险产品、职业类别费率、保单详情和 Excel 保单导出。
- 保费/使用费双账户、充值、日消耗和月消耗预估。
- 工伤报案、7 项材料清单、图片/PDF/Office 上传、补件、保司审核和赔付时间线。
- 参停保名单、保司 API/邮件发送、经营报表、消息中心和服务连接设置。
