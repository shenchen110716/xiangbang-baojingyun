from typing import Literal

from pydantic import BaseModel


class EnrollmentReceiptIn(BaseModel):
    # 人工标记保司回执：confirmed=已确认 / pending=待回执。
    status: Literal["pending", "confirmed"] = "confirmed"
    note: str = ""
