from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


class LoginIn(BaseModel): username: str; password: str; portal: Literal["admin","enterprise","salesperson"] = "admin"
class PasswordChangeIn(BaseModel): current_password: str; new_password: str = Field(min_length=6,max_length=128)
class TokenOut(BaseModel): access_token: str; token_type: str = "bearer"
class UserOut(BaseModel): model_config = ConfigDict(from_attributes=True); id: int; username: str; name: str; role: str; enterprise_id: Optional[int] = None; enterprise_role: Optional[Literal["owner", "project_manager"]] = None; phone: str = ""; is_owner: bool = False; active: bool = True
