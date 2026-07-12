from typing import Literal, Optional

from pydantic import BaseModel, Field


class PositionIn(BaseModel): enterprise_id: Optional[int] = None; actual_employer: str; actual_employer_id: Optional[int] = None; name: str; occupation_class: Literal["1-3类","4类","5类","超5类"] = "1-3类"; plan_id: Optional[int] = None
class ActualEmployerIn(BaseModel): enterprise_id: Optional[int] = None; name: str; credit_code: str = ""; contact: str = ""; phone: str = ""
class ActualEmployerUpdate(BaseModel): name: Optional[str] = Field(default=None,min_length=1); credit_code: Optional[str] = None; contact: Optional[str] = None; phone: Optional[str] = None
class PositionVideoIn(BaseModel): name: str; url: str = ""
class PositionVideoReviewIn(BaseModel): status: Literal["pending","approved","rejected","supplement"]; review_note: str = ""
class PositionReviewIn(BaseModel): occupation_class: Optional[Literal["1-3类","4类","5类","超5类"]] = None; status: Literal["approved","rejected","supplement"] = "approved"; plan_id: Optional[int] = None; review_note: str = ""
