-- 容器启动时以管理员执行:先建受限用户(此时还没有 schema,故不授权)
CREATE USER identity_user WITH PASSWORD 'identity_pw';
CREATE USER org_user WITH PASSWORD 'org_pw';
