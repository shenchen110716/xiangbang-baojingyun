from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import EnterprisePremiumAccount, InsurerAccount, InsurerAccountLink
from .serialization import amount, serialize


# 平台使用费没有"保司"，但它同样需要一个收款账户告诉企业往哪里转账。为了不新建
# 一张表/一列（免迁移），复用 InsurerAccountLink：用这个保留键把某个收款账户标记
# 为"平台使用费收款账户"。管理员在收款账户管理里用这个保司名绑定即可。
PLATFORM_USAGE_INSURER_KEY = "平台使用费"


def resolve_account_for_insurer(session: Session, insurer: str) -> InsurerAccount | None:
    link = session.scalar(select(InsurerAccountLink).where(InsurerAccountLink.insurer == insurer))
    if not link:
        return None
    account = session.get(InsurerAccount, link.account_id)
    return account if account and account.status == "active" else None


def usage_payment_account(session: Session) -> InsurerAccount | None:
    """平台使用费的收款账户（通过保留键映射解析），未配置则 None。"""
    return resolve_account_for_insurer(session, PLATFORM_USAGE_INSURER_KEY)


def premium_payment_options(session: Session) -> list[dict]:
    """已配置收款账户的保费保司列表，供企业端充值时下拉选择（选保司即带出收款账户），
    排除平台使用费保留键与已停用账户。"""
    options: list[dict] = []
    for link in session.scalars(select(InsurerAccountLink).where(InsurerAccountLink.insurer != PLATFORM_USAGE_INSURER_KEY)):
        account = session.get(InsurerAccount, link.account_id)
        if not account or account.status != "active":
            continue
        options.append({
            "insurer": link.insurer,
            "label": account.label,
            "bank_name": account.bank_name,
            "account_no": account.account_no,
            "account_holder": account.account_holder,
        })
    return options


def recharge_payment_account_view(session: Session, account_type: str, insurer: str = "") -> dict | None:
    """企业/管理员发起充值时要展示的收款账户（户名/开户行/账号），按账户类型解析：
    保费按所选保司，使用费按平台使用费保留键。未配置返回 None，前端提示联系平台。"""
    if account_type == "usage":
        account = usage_payment_account(session)
    elif account_type == "premium" and insurer.strip():
        account = resolve_account_for_insurer(session, insurer.strip())
    else:
        account = None
    if not account:
        return None
    return {
        "label": account.label,
        "bank_name": account.bank_name,
        "account_no": account.account_no,
        "account_holder": account.account_holder,
        # 共用账户下也一并展示，但隐藏内部的平台使用费保留键。
        "insurers": [i for i in insurers_for_account(session, account.id) if i != PLATFORM_USAGE_INSURER_KEY],
    }


def insurers_for_account(session: Session, account_id: int) -> list[str]:
    return [row[0] for row in session.execute(select(InsurerAccountLink.insurer).where(InsurerAccountLink.account_id == account_id)).all()]


def insurer_account_dict(item: InsurerAccount, session: Session) -> dict:
    return {**serialize(item), "insurers": insurers_for_account(session, item.id)}


def get_or_create_premium_account(session: Session, enterprise_id: int, account_id: int) -> EnterprisePremiumAccount:
    row = session.scalar(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == enterprise_id, EnterprisePremiumAccount.account_id == account_id))
    if row:
        return row
    row = EnterprisePremiumAccount(enterprise_id=enterprise_id, account_id=account_id, balance=0)
    session.add(row)
    session.flush()
    return row


def premium_accounts_for_enterprise(session: Session, enterprise_id: int) -> list[dict]:
    rows = session.scalars(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == enterprise_id))
    result = []
    for row in rows:
        account = session.get(InsurerAccount, row.account_id)
        if not account:
            continue
        insurers = insurers_for_account(session, row.account_id)
        # 平台使用费收款账户复用了同一张收款账户表（保留键映射），它属于使用费账户，
        # 绝不能出现在“保费账户余额”里，否则保费列表/大屏会混入使用费账户。
        if PLATFORM_USAGE_INSURER_KEY in insurers:
            continue
        result.append({
            "account_id": row.account_id,
            "label": account.label,
            "insurers": insurers,
            "balance": amount(row.balance),
        })
    return result
