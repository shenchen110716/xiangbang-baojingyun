from typing import Literal, Optional

from pydantic import BaseModel, Field


class PositionIn(BaseModel): enterprise_id: Optional[int] = None; actual_employer: str; actual_employer_id: Optional[int] = None; name: str; occupation_class: Literal["1-3类","4类","5类","超5类"] = "1-3类"; plan_id: Optional[int] = None; enable_personal_pay: bool = False; enable_employer_pay: bool = False
# 名称和信用代码必须校验准确性（用户反馈 2026-07-30 第 2 条）：credit_code
# 从可选改成必填，格式/校验位在路由层用 is_valid_credit_code 校验。
class ActualEmployerIn(BaseModel): enterprise_id: Optional[int] = None; name: str = Field(min_length=2,max_length=160); credit_code: str = Field(min_length=1,max_length=40); contact: str = ""; phone: str = ""
class ActualEmployerUpdate(BaseModel): name: Optional[str] = Field(default=None,min_length=2); credit_code: Optional[str] = Field(default=None,min_length=1); contact: Optional[str] = None; phone: Optional[str] = None
class PositionVideoIn(BaseModel): name: str; url: str = ""
class PositionVideoReviewIn(BaseModel): status: Literal["pending","approved","rejected","supplement"]; review_note: str = ""
class PositionReviewIn(BaseModel): occupation_class: Optional[Literal["1-3类","4类","5类","超5类"]] = None; status: Literal["approved","rejected","supplement"] = "approved"; plan_id: Optional[int] = None; review_note: str = ""
# 扫码参保：免登录公开提交。website 是蜜罐字段，跟 EnterpriseApplyIn 同一个思路——
# 正常人看不到这个字段，机器人乱填表单时经常会带上。
class EnrollSubmitIn(BaseModel): name: str = Field(min_length=1,max_length=80); id_number: str = Field(min_length=6,max_length=40); phone: str = Field(default="",max_length=30); website: str = Field(default="",max_length=200)
class EnrollReviewIn(BaseModel): status: Literal["approved","rejected"]; review_note: str = ""
