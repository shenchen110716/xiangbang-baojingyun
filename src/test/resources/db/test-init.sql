-- 容器启动时以管理员执行:先建受限用户(此时还没有 schema,故不授权)
CREATE USER identity_user WITH PASSWORD 'identity_pw';
CREATE USER org_user WITH PASSWORD 'org_pw';
CREATE USER job_user WITH PASSWORD 'job_pw';
CREATE USER engagement_user WITH PASSWORD 'engagement_pw';
CREATE USER settlement_user WITH PASSWORD 'settlement_pw';
CREATE USER fund_user WITH PASSWORD 'fund_pw';
CREATE USER broker_user WITH PASSWORD 'broker_pw';
CREATE USER profile_user WITH PASSWORD 'profile_pw';
CREATE USER matching_user WITH PASSWORD 'matching_pw';
CREATE USER review_user WITH PASSWORD 'review_pw';
