from typing import Literal, Optional

from pydantic import BaseModel, Field


class ClaimIn(BaseModel): enterprise_id: int; person_id: int; description: str; amount: float = Field(default=0,ge=0); medical_cost: float = Field(default=0,ge=0); accident_at: str; accident_place: str; accident_type: str = "工伤事故"; hospital: str = ""; diagnosis: str = ""; contact_name: str = ""; contact_phone: str = ""
class ClaimUpdate(BaseModel): description: Optional[str]=None; hospital:Optional[str]=None; diagnosis:Optional[str]=None; medical_cost:Optional[float]=Field(default=None,ge=0); amount:Optional[float]=Field(default=None,ge=0); contact_name:Optional[str]=None; contact_phone:Optional[str]=None; insurer_report_no:Optional[str]=None; current_handler:Optional[str]=None; deadline:Optional[str]=None; sla_deadline:Optional[str]=None; rejection_reason:Optional[str]=None; review_note:Optional[str]=None; risk_level:Optional[Literal['normal','attention','high']]=None
class ClaimStatusIn(BaseModel): status: Literal["reported","collecting","submitted","insurer_review","supplement","approved","paid","rejected","closed"]; note: str = ""; approved_amount: Optional[float] = Field(default=None,ge=0); insurer_report_no: Optional[str] = None; rejection_reason: Optional[str] = None; paid_at: Optional[str] = None; current_handler: Optional[str] = None; sla_deadline: Optional[str] = None
class ClaimDocumentIn(BaseModel): name: str; url: str = ""; doc_type: str = "other"
class ClaimDocumentReviewIn(BaseModel): status: Literal["uploaded","accepted","rejected"]; review_note: str = ""
