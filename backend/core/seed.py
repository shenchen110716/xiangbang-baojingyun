import os

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Enterprise, User
from .security import pwd


def seed_default_accounts(s: Session) -> None:
    if not s.scalar(select(User).where(User.username == "admin")):
        s.add(User(username="admin", password_hash=pwd.hash(os.getenv("ADMIN_PASSWORD", "admin123")), name="响帮帮管理员", role="admin"))
    s.commit()
    # 用户电脑端演示账号：仅在数据库尚未配置参保单位账号时创建
    if not s.scalar(select(User).where(User.username == "enterprise")):
        demo_enterprise = s.scalar(select(Enterprise).order_by(Enterprise.id.asc()))
        if not demo_enterprise:
            demo_enterprise = Enterprise(name="演示参保单位", kind="企业", contact="演示管理员", phone="", status="active")
            s.add(demo_enterprise); s.flush()
        s.add(User(username="enterprise", password_hash=pwd.hash(os.getenv("ENTERPRISE_PASSWORD", "enterprise123")), name=f"{demo_enterprise.name}管理员", role="enterprise", enterprise_id=demo_enterprise.id, is_owner=True))
        s.commit()
    # 旧数据升级：每个投保单位至少保留一个可管理操作员的单位主管。
    enterprise_ids={row[0] for row in s.execute(select(User.enterprise_id).where(User.role=="enterprise",User.enterprise_id.is_not(None))).all()}
    for enterprise_id in enterprise_ids:
        if not s.scalar(select(User).where(User.role=="enterprise",User.enterprise_id==enterprise_id,User.is_owner.is_(True))):
            owner=s.scalar(select(User).where(User.role=="enterprise",User.enterprise_id==enterprise_id).order_by(User.id.asc()))
            if owner: owner.is_owner=True
    s.commit()
