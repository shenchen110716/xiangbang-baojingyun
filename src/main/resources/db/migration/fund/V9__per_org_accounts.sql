-- 资金账户改为**按单位分账**(老板 2026-08-06 选了乙:机构自己充值、自己发薪)。
--
-- 改之前:escrow_account 的主键就是 account_type —— 一个类型一个账户,全平台共用。
-- 于是"机构的钱"和"平台的钱"混在同一个余额里,谁充的、发给谁的,只能靠流水的
-- reason 文本去猜。按单位分账之后,每家机构的余额是独立的一行,扣款只能扣自己那行。
--
-- **org_id 为 NULL = 平台自己的账户。**沿用本代码库已有的写法
-- (commission_scheme / commission_rate 都是"NULL 表示平台默认"),
-- 而不是用 0 当哨兵值 —— 0 是个合法的 BIGINT,哪天真有 id=0 的组织就分不清了。
--
-- **老数据全部落成平台账户,余额一分不动。**现在库里的余额本来就是平台在管,
-- 迁移的目标是行为不变。

-- 主键从 account_type 换成代理键。直接改成 (org_id, account_type) 复合主键不行:
-- 主键列不允许 NULL,而"平台自己"正是靠 NULL 表达的
ALTER TABLE fund.escrow_account DROP CONSTRAINT escrow_account_pkey;
ALTER TABLE fund.escrow_account ADD COLUMN id BIGSERIAL PRIMARY KEY;
ALTER TABLE fund.escrow_account ADD COLUMN org_id BIGINT;

-- 一家单位的一种账户只能有一行。两行的话余额要靠 SUM 才对得上,
-- 而扣款只会扣到其中一行 —— 另一行的钱谁也取不出来
CREATE UNIQUE INDEX escrow_account_org_type_idx
    ON fund.escrow_account (org_id, account_type) WHERE org_id IS NOT NULL;

CREATE UNIQUE INDEX escrow_account_platform_type_idx
    ON fund.escrow_account (account_type) WHERE org_id IS NULL;

-- 账本也要带上单位,否则按单位对账时只能靠 reason 文本去猜是谁的钱
ALTER TABLE fund.escrow_ledger ADD COLUMN org_id BIGINT;
CREATE INDEX escrow_ledger_org_idx ON fund.escrow_ledger (org_id, id DESC);

-- 代发单要知道钱从哪个单位的账户出。
--
-- **此前它压根不知道** —— payout 只有结算单号和收款人。全平台一个账户时
-- 这不成问题(反正都从那一个账户扣);按单位分账之后,不知道归属就等于
-- 不知道该扣谁的钱,而扣错了是把 A 公司的钱发给了 B 公司的工人。
--
-- 可空:老的代发单没有这个信息,它们从平台账户出(和迁移前一致)。
ALTER TABLE fund.payout ADD COLUMN org_id BIGINT;
CREATE INDEX payout_org_idx ON fund.payout (org_id, id DESC);

-- 铁律 1:新序列要显式授权。
-- `ADD COLUMN id BIGSERIAL` 会**顺手建一个序列**,而 V1 里那句
-- `GRANT ... ON ALL SEQUENCES` 只对当时已存在的序列生效 ——
-- 不补这一条,机构第一次充值时报 "permission denied for sequence"。
GRANT USAGE, SELECT ON SEQUENCE fund.escrow_account_id_seq TO fund_user;
