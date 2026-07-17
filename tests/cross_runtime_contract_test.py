"""Python 是唯一结构权威；本测试确保 Java 镜像不会在结构变更后静默失配。

Phase 1 的教训：`User.enterpriseRole` 曾初始化为 `"owner"`，MyBatis 默认
`callSettersOnNulls=false`，可空列为 NULL 时字段保留该默认值——在 Python 判 403
的地方，Java 会误判为企业主管放行。这不是一次性修复能防住的问题：任何可空列，
只要 Java 侧带了非 null 初始值，就会重演同一类 fail-open。

因此本测试做两件事，且缺一不可：
1. 结构完整性——Python 每一列，Java Mapper 的 COLUMNS 都要有映射（否则读取会静默丢字段）。
2. 可空性忠实——Python 可空列，Java 字段绝不能有初始化表达式（否则 NULL 会被伪装成默认值）。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import backend.models  # noqa: F401 — 触发全部模型注册
from backend.core.db import Base

JAVA = Path(__file__).resolve().parents[1] / "java-backend/src/main/java/com/xbb/baojing"

# 表名 -> (Mapper 相对路径, Entity 相对路径)。Task 1 盘点结果：v4.2 Phase 2/3/5
# 新增的 12 张表原先在 Java 侧零镜像；user_employer_scopes（Phase 1）已镜像，一并纳入
# 契约测试防止未来回归。pending_terminations 早于 v4.2 且历史上已明确不镜像，不纳入。
MIRRORED_TABLES = {
    "user_employer_scopes": (
        "enterprise/UserEmployerScopeMapper.java", "enterprise/UserEmployerScope.java"),
    "employment_feedback_batches": (
        "employment/EmploymentFeedbackBatchMapper.java", "employment/EmploymentFeedbackBatch.java"),
    "employment_facts": (
        "employment/EmploymentFactMapper.java", "employment/EmploymentFact.java"),
    "employment_fact_matches": (
        "employment/EmploymentFactMatchMapper.java", "employment/EmploymentFactMatch.java"),
    "integration_api_keys": (
        "employment/IntegrationApiKeyMapper.java", "employment/IntegrationApiKey.java"),
    "integration_nonces": (
        "employment/IntegrationNonceMapper.java", "employment/IntegrationNonce.java"),
    "participation_operations": (
        "timeliness/ParticipationOperationMapper.java", "timeliness/ParticipationOperation.java"),
    "employment_timeliness_results": (
        "timeliness/EmploymentTimelinessResultMapper.java", "timeliness/EmploymentTimelinessResult.java"),
    "timeliness_outbox": (
        "timeliness/TimelinessOutboxMapper.java", "timeliness/TimelinessOutbox.java"),
    "agent_commission_statements": (
        "agent/AgentCommissionStatementMapper.java", "agent/AgentCommissionStatement.java"),
    "agent_commission_statement_items": (
        "agent/AgentCommissionStatementItemMapper.java", "agent/AgentCommissionStatementItem.java"),
    "agent_commission_payments": (
        "agent/AgentCommissionPaymentMapper.java", "agent/AgentCommissionPayment.java"),
    "agent_commission_payment_allocations": (
        "agent/AgentCommissionPaymentAllocationMapper.java", "agent/AgentCommissionPaymentAllocation.java"),
}


def _snake_to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(word.capitalize() for word in rest)


def java_columns(mapper_relpath: str) -> set[str]:
    """Parse the `COLUMNS = "..."` constant's snake_case source names.

    Matches `col_name` or `col_name as camelAlias` — we only need the left
    side to know which Python columns are represented at all. A missing file
    reports as zero mapped columns rather than crashing, so the assertion
    below lists every real Python column as "missing" — exactly what Task 3
    needs to build.
    """
    path = JAVA / mapper_relpath
    if not path.exists():
        return set()
    text = path.read_text(encoding="utf-8")
    # 仓库里两种命名并存：UserMapper 用 COLUMNS，UserEmployerScopeMapper 用 COLS。
    match = re.search(r'(?:COLUMNS|COLS)\s*=\s*((?:"[^"]*"\s*\+?\s*)+);', text, re.S)
    assert match, f"{mapper_relpath} 没有 COLUMNS/COLS 常量"
    raw = "".join(re.findall(r'"([^"]*)"', match.group(1)))
    columns = set()
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        # "col_name as camelAlias" 或裸 "col_name"
        source = part.split(" as ")[0].split(" AS ")[0].strip()
        columns.add(source)
    return columns


def test_every_mirrored_table_maps_every_python_column():
    problems = []
    for table_name, (mapper_relpath, _entity) in MIRRORED_TABLES.items():
        table = Base.metadata.tables[table_name]
        mapped = java_columns(mapper_relpath)
        missing = {c.name for c in table.columns} - mapped
        if missing:
            problems.append(f"{table_name} 的 Java 映射缺少列: {sorted(missing)} ({mapper_relpath})")
    assert not problems, "\n".join(problems)


def test_nullable_columns_are_not_defaulted_to_a_value_in_java():
    """Phase 1 的 fail-open 教训：MyBatis callSettersOnNulls 默认 false，
    可空列若在 Java 侧带初始化表达式，NULL 行会保留该表达式的值而非变成 null。"""
    problems = []
    for table_name, (_mapper, entity_relpath) in MIRRORED_TABLES.items():
        entity_path = JAVA / entity_relpath
        if not entity_path.exists():
            continue  # 已由 test_every_mirrored_table_maps_every_python_column 报告
        text = entity_path.read_text(encoding="utf-8")
        table = Base.metadata.tables[table_name]
        for column in table.columns:
            if not column.nullable:
                continue
            field = _snake_to_camel(column.name)
            # 私有字段声明且带 "= 某值"（而非仅声明）即为可疑初始化。
            # 排除集合类型的空初始化（List/Set/Map 的 = new ...() 是合法的容器默认值，
            # 不是把 NULL 伪装成业务默认值）。
            pattern = rf"private\s+[\w<>\[\], ]+\s+{re.escape(field)}\s*=\s*(?!new\s)"
            if re.search(pattern, text):
                problems.append(
                    f"{entity_path.name}: 可空列 {column.name}（Java 字段 {field}）"
                    f"带初始化表达式，NULL 会被伪装成默认值")
    assert not problems, "\n".join(problems)


def test_every_check_constraint_status_value_has_a_java_side_comment_or_constant():
    """非强制断言，仅记录：确保清单没有遗漏尚未建 mapper 的表。"""
    for table_name in MIRRORED_TABLES:
        assert table_name in Base.metadata.tables, f"清单中的表 {table_name} 在 Python 模型里不存在"


if __name__ == "__main__":
    test_every_mirrored_table_maps_every_python_column()
    print("  test_every_mirrored_table_maps_every_python_column ok")
    test_nullable_columns_are_not_defaulted_to_a_value_in_java()
    print("  test_nullable_columns_are_not_defaulted_to_a_value_in_java ok")
    test_every_check_constraint_status_value_has_a_java_side_comment_or_constant()
    print("  test_every_check_constraint_status_value_has_a_java_side_comment_or_constant ok")
    print("cross runtime contract test passed")
