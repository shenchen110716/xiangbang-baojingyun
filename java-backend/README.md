# 响帮帮无忧保 — Java 后端（Spring Boot + MyBatis）

这是 `backend/`（FastAPI + SQLAlchemy）的完整 Java 重新实现，API 契约（路径、方法、请求/响应 JSON 字段名）与原后端逐一对齐，因此 `web/`（Vue3 管理后台）和 `miniprogram/`（微信小程序）**无需任何修改**即可直接连接这套新后端。

## 技术栈

- **Spring Boot 3.3** + **Java 21**
- **MyBatis**（注解式 mapper，个别列表查询用内联 `<script>` 动态 SQL）代替 SQLAlchemy
- **Flyway** 管理数据库结构（`src/main/resources/db/migration/V1__baseline.sql`），对应原项目 4 个 Alembic 迁移文件合并后的最终形态
- **H2**（本地开发，文件模式 + PostgreSQL 兼容模式）/ **PostgreSQL**（生产）—— 与原 Python 后端"本地 SQLite / 生产 Postgres"的双数据库策略对应
- **jjwt** 签发/校验 JWT（HS256，claims 与原后端一致：`sub`=用户ID、`sv`=会话版本号）
- **Spring Security** 仅用于 `BCryptPasswordEncoder` 和过滤器链基础设施——认证与鉴权逻辑是手写的（`JwtAuthFilter` + `Rbac` 工具类），不依赖 Spring Security 自身的认证模型，逐行对应原 Python 的 `core/security.py` / `core/rbac.py`
- **Apache POI** 生成保单 Excel 导出（对应原来的 openpyxl）

## 关键设计决策

### JSON 字段命名：全局 snake_case

前端（Vue3 + 小程序）是照着原 Pydantic 后端的 snake_case JSON 契约写的（`enterprise_id`、`insurance_base_price`、`created_at` ……）。Java 端没有改动任何前端代码，而是在 `application.yml` 里把 Jackson 的属性命名策略设为 `SNAKE_CASE`，让 Java 侧的驼峰命名字段自动与 JSON 里的下划线命名字段互转：

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

个别布尔字段（如 `User.isOwner()`）经过 JavaBean 自省会变成 `owner` 而不是期望的 `is_owner`，这几处用了显式的 `@JsonProperty("is_owner")` 覆盖。

### 定价字段的"展开"（flatten）

原 Python 大量使用 `{**serialize(x), **pricing_snapshot(...)}` 把计价字段直接铺平到响应对象里（前端类型是 `InsuredPerson extends Partial<PricingSnapshot>` 这种"继承式"设计，不是嵌套对象）。Java 对应做法是给这些 DTO 加一个 `pricing` 字段，getter 标 `@JsonUnwrapped`，效果与 Python 的字典展开完全一致。

### 静态前端服务：显式路由白名单，不是通配 fallback

`web/dist/` 由 `StaticFrontendConfig`（`/assets/**` 的 `ResourceHandler`）+ `StaticFrontendController`（SPA history 模式的路由回退）一起提供服务。SPA 回退**只认识 19 个已知的前端路由**（照抄 `web/src/router/routes.ts`），不是"任何未匹配路径都返回 index.html"的万能兜底——否则 `/data.db`、`/backend/app.py` 这类路径会返回 200（首页），比原来 Phase 0 修复的"限定访问三个文件"还退步。未知路径一律 404。

### 密码哈希：BCrypt，不兼容原 pbkdf2_sha256 哈希

原后端用 `passlib` 的 `pbkdf2_sha256`。Java 端改用 Spring Security 标准的 BCrypt——这是一套全新的数据库（不与原 SQLite/Postgres 共享数据），不存在需要兼容旧哈希值登录的场景，所以没有必要照搬 pbkdf2_sha256 的实现细节。

## 项目结构

按业务域（feature package）组织，而不是按类型（models/services/controllers 分层）分包——每个业务域下面的 Controller、Service、Mapper、实体类放在同一个包里，方便按功能定位代码：

```
src/main/java/com/xbb/baojing/
  Application.java
  common/          JWT、RBAC、审计日志、签名下载令牌、User 实体+Mapper、全局异常处理、生产配置校验
  auth/            登录、修改密码
  enterprise/      投保单位 + 实际工作单位
  position/        岗位 + 岗位视频（上传/审核）
  plan/            保险方案 + 职业类别分档定价 + 定价引擎（PricingService）
  insured/         参保员工 + 保单 + PolicyMember（保障期间桥接）
  claim/           工伤理赔（状态机、材料清单、时间线）
  agent/           业务员 + 业务计价关系
  operator/        单位操作员账号
  finance/         发票、支付、账本（LedgerEntry）
  enrollment/       参停保导出/邮件发送
  dashboard/       首页看板、经营大屏、报表、账单、消息中心、审计日志、健康检查
  web/             web/dist 的静态资源服务
```

## 本地运行

### 前置依赖

- **JDK 21**（本仓库开发时用的是 Eclipse Temurin 21）
- **Maven 3.9+**
- 本地开发数据库用 **H2**（内嵌文件数据库，无需单独安装/启动任何数据库服务）
- 如果要重新构建前端（`web/`），需要 **Node.js 20+**；如果只是运行已经构建好的 `web/dist/`，不需要 Node

如果机器上还没有 JDK/Maven，且不方便用包管理器安装，可以直接下载官方二进制包解压使用（不需要管理员权限）：

```bash
# 下载 Temurin JDK 21（以 macOS arm64 为例，其它平台去 https://adoptium.net 选对应版本）
curl -fsSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
tar -xzf jdk.tar.gz

# 下载 Maven
curl -fsSL -o maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz"
tar -xzf maven.tar.gz

export JAVA_HOME="$(pwd)/jdk-21.0.11+10/Contents/Home"   # macOS 路径；Linux 是 jdk-21.0.11+10 目录本身
export PATH="$JAVA_HOME/bin:$(pwd)/apache-maven-3.9.9/bin:$PATH"
```

### 启动

```bash
cd java-backend
mvn spring-boot:run
```

默认监听 `8080` 端口（`PORT` 环境变量可覆盖），`dev` profile 默认激活（`SPRING_PROFILES_ACTIVE` 环境变量可覆盖为 `prod`）。

第一次启动会自动：
1. 通过 Flyway 在 `./data/baojing.mv.db`（H2 文件数据库，相对于 `java-backend/` 目录）建表
2. 创建默认管理员账号 `admin` / `admin123`（可用 `ADMIN_PASSWORD` 环境变量覆盖初始密码）
3. 创建一个演示参保单位和演示账号 `enterprise` / `enterprise123`（可用 `ENTERPRISE_PASSWORD` 覆盖）

打开浏览器访问 **http://127.0.0.1:8080/** ——这个 Java 后端同时也把 `web/` 目录下已经 `npm run build` 好的 Vue 管理后台serve 出来了（见下方"前端"一节），不需要单独起前端开发服务器就能直接用。

### 只想验证 API，不用界面

```bash
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","portal":"admin"}'
```

拿到 `access_token` 后，后续请求带 `Authorization: Bearer <token>` 头即可。

## 前端

`web/`（Vue3 + Element Plus 管理后台）**完全不需要改动**——它是这次会话早些时候针对 FastAPI 后端的 API 契约写的，Java 后端逐字段对齐了同一套契约，所以直接能用。

如果 `web/dist/` 还没构建过，或者改过前端代码需要重新构建：

```bash
cd web
npm install
npm run build
```

Java 后端读取 `web/dist/` 的相对路径由 `application.yml` 里的 `app.web-dist-dir` 控制，默认是 `../web/dist`（相对于 `java-backend/` 运行目录）。生产部署（见下方 Docker）里这个路径会不一样，已经在 `Dockerfile` 里对应调整好。

微信小程序（`miniprogram/`）同样不需要改动，把它请求的 API 基础地址指向这个 Java 后端的地址即可（本地开发默认还是 `http://127.0.0.1:8001/api`，如果 Java 后端换了端口，记得在小程序端同步改）。

## 生产部署

### 环境变量

| 变量 | 说明 | 生产环境是否必填 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 设为 `prod` 启用 PostgreSQL 数据源 | 是 |
| `ENVIRONMENT` | 设为 `production` 会触发启动时的配置完整性检查（`ProductionConfigChecker`），缺任何一项直接拒绝启动，不会用不安全的默认值悄悄跑起来 | 是 |
| `DATABASE_URL` | JDBC 格式的 PostgreSQL 连接串，如 `jdbc:postgresql://host:5432/dbname` | 是 |
| `JWT_SECRET` | 至少 32 字节的随机字符串，不能是仓库里的开发默认值 | 是 |
| `ADMIN_PASSWORD` | 初始管理员密码，不能是 `admin123` | 是 |
| `ENTERPRISE_PASSWORD` | 演示企业账号密码 | 否（不需要演示账号可以不管） |
| `PORT` | 监听端口 | 否，默认 8080 |
| `CORS_ORIGINS` | 逗号分隔的允许来源，默认 `*` | 否 |
| `INTEGRATION_MODE` | `mock`（默认，不接真实保司/短信/邮件/支付接口）或 `real` | 否 |
| `UPLOADS_DIR` | 岗位视频/理赔材料的本地存储目录 | 否，默认 `./uploads` |

### Docker

```bash
cd java-backend
docker build -t xbb-java-backend .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e ENVIRONMENT=production \
  -e DATABASE_URL=jdbc:postgresql://your-host:5432/your-db \
  -e JWT_SECRET="$(openssl rand -hex 32)" \
  -e ADMIN_PASSWORD="换成真实密码" \
  xbb-java-backend
```

`Dockerfile` 是多阶段构建：第一阶段用 `node:22` 构建 `web/` 前端产物，第二阶段用 Maven 构建 Java 后端并把前端产物一起打进最终镜像——单个镜像，不需要额外部署前端。

### 打包为可执行 JAR（不用 Docker 的场景）

```bash
cd java-backend
mvn clean package -DskipTests
java -jar target/baojing.jar \
  --spring.profiles.active=prod \
  -DENVIRONMENT=production \
  -DDATABASE_URL=jdbc:postgresql://your-host:5432/your-db \
  -DJWT_SECRET=xxxxx \
  -DADMIN_PASSWORD=xxxxx
```

注意：JAR 本身不包含 `web/dist/`——`app.web-dist-dir` 配置的是**相对路径**，用 JAR 方式部署时要保证运行目录旁边有构建好的 `web/dist/` 目录（或者改配置指向绝对路径），否则打开首页会 404。Docker 方式没有这个问题，因为前端产物已经打进镜像了。

## 与原 Python 后端的行为差异（有意为之，不是遗漏）

- 密码哈希算法从 pbkdf2_sha256 换成 BCrypt（见上"关键设计决策"，两套系统数据库独立，无需兼容）。
- JWT 签名算法保持 HS256，但底层库从 PyJWT 换成 jjwt——两者对同一个 `JWT_SECRET` 签出的 token 格式兼容，只是不能跨语言复用同一个已签发的 token（重新登录即可，不影响功能）。
- Excel 导出库从 openpyxl 换成 Apache POI，导出的 `.xlsx` 文件内容字段一致，只是生成库不同。

## 测试情况

这套 Java 后端在真实运行的 H2 数据库上，通过实际 HTTP 请求手动验证过以下关键链路，均与原 Python 系统行为一致（含具体金额计算结果比对）：

- 登录 / 会话（JWT 签发校验、密码错误 401、账号停用 403）
- 投保单位 CRUD、充值 + 账本流水、企业自助充值被拒绝（403，对应 Phase 0 安全修复）
- 岗位创建 → 视频上传 → 审核定类（含"无视频不能定类通过"的校验）
- 保险方案定价引擎（保险原价/总返佣/保司结算底价/销售最低价的计算，与原系统 `system_smoke.py` 测试用例的已知正确值完全一致）
- 参保员工创建 → 参保激活 → PolicyMember 保障期间桥接（自动建 Policy、生成计价快照）
- 理赔案件完整状态机：报案→材料收集（首次上传自动流转）→提交→保司审核→核赔通过→赔付→归档，含每一步的业务校验（材料不齐全不能提交、无核赔金额不能通过、无拒赔原因不能拒赔）
- 经营看板聚合统计、经营大屏产品分布
- 静态前端服务：根路径、SPA 路由回退、资源文件服务、source/db 路径 404 拒绝

尚未做的（如果要投入生产，建议补上）：自动化测试套件（对应原项目的 `tests/system_smoke.py` + `tests/security_smoke.py`，这次是手动 curl 验证，没有写成可重复运行的 JUnit 测试）、跨租户越权的系统性测试、微信小程序端的联调验证。
