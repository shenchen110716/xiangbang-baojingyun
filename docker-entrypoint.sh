#!/bin/sh
# 容器启动:①把托管库的连接串转成应用要的形式 ②引导 schema 与域用户 ③起应用。
#
# 为什么引导放在这里:Render 这类托管 Postgres 没有 docker-entrypoint-initdb.d 钩子,
# 本地 compose 靠那个钩子建 20 个 schema 与最小权限用户。要让同一套东西在云上也成立,
# 引导只能由应用侧做。SQL 全程 IF NOT EXISTS,重复执行安全。
set -e

# ---- ① 连接串 ----
# Render 注入 postgres://USER:PW@HOST:PORT/DB;应用要 JDBC 串 + 分开的用户/口令。
# 在这里转换的好处是:凭据不必写进 render.yaml,也就不会进仓库。
if [ -n "$DATABASE_URL" ] && [ -z "$DB_URL" ]; then
  _rest=${DATABASE_URL#*://}
  _cred=${_rest%%@*}
  _hostdb=${_rest#*@}
  DB_ADMIN_USER=${_cred%%:*}
  DB_ADMIN_PASSWORD=${_cred#*:}
  DB_URL="jdbc:postgresql://${_hostdb}"
  export DB_ADMIN_USER DB_ADMIN_PASSWORD DB_URL
fi

: "${DB_URL:?DB_URL 未设置(也没有 DATABASE_URL 可推导)}"
: "${DB_ADMIN_USER:?DB_ADMIN_USER 未设置}"
: "${DB_ADMIN_PASSWORD:?DB_ADMIN_PASSWORD 未设置}"
: "${DOMAIN_DB_PASSWORD:?DOMAIN_DB_PASSWORD 未设置}"

# 口令要参与 sed 替换(见下)。含 / & | 这类字符会被 sed 当语法,
# 结果是**悄悄建出一个口令不对的角色**,然后应用连不上、报的却是认证失败。
# 与其让它错得难查,不如在这里直接拒。
case "$DOMAIN_DB_PASSWORD" in
  *[!a-zA-Z0-9]*)
    echo "DOMAIN_DB_PASSWORD 只能是字母和数字:引导脚本用 sed 替换占位符,特殊字符会被误解释。" >&2
    exit 1 ;;
esac

# ---- ② 引导 schema 与域用户 ----
INIT_SQL=/app/db/init-schemas.sql

# 域清单从 SQL 里推导,不另存一份。
# 这个清单在 init-schemas.sql、SchemaIsolationTests、compose 里各有一份已经够多了,
# 再抄第四份迟早对不上,而对不上的症状是"某个域静默没被建出来"。
DOMAINS=$(grep -oE 'CREATE SCHEMA IF NOT EXISTS [a-z_]+' "$INIT_SQL" | awk '{print $NF}')

if [ "${XBB_BOOTSTRAP_DB:-true}" = "true" ]; then
  _hostdb=${DB_URL#jdbc:postgresql://}
  echo "引导数据库:$(echo "$DOMAINS" | wc -l | tr -d ' ') 个域的 schema 与最小权限用户"
  sed "s/:[A-Z_]*_PW:/${DOMAIN_DB_PASSWORD}/g" "$INIT_SQL" \
    | PGPASSWORD="$DB_ADMIN_PASSWORD" psql -v ON_ERROR_STOP=1 -q \
        "postgresql://${DB_ADMIN_USER}@${_hostdb}?sslmode=${PGSSLMODE:-prefer}"
  echo "引导完成"
fi

# 每个域的连接身份同样由清单推导,避免在 render.yaml 里手写 40 个环境变量。
for _d in $DOMAINS; do
  _u=$(echo "$_d" | tr 'a-z' 'A-Z')
  eval "export DB_${_u}_USER=\"${_d}_user\""
  eval "export DB_${_u}_PASSWORD=\"\$DOMAIN_DB_PASSWORD\""
done

# ---- ③ 起应用 ----
# Render 用 $PORT 告诉容器该监听哪个端口;本地没有这个变量时退回 8080。
exec java $JAVA_OPTS -Dserver.port="${PORT:-8080}" -jar /app/app.jar
