from sqlalchemy.orm import Session

from ..models import Enterprise, User


def operator_dict(item:User,session:Session):
    enterprise=session.get(Enterprise,item.enterprise_id) if item.enterprise_id else None
    return {"id":item.id,"username":item.username,"name":item.name,"phone":item.phone,"role":item.role,"enterprise_id":item.enterprise_id,"enterprise_name":enterprise.name if enterprise else "","enterprise_role":item.enterprise_role,"is_owner":item.is_owner,"active":item.active,"created_at":item.created_at}
