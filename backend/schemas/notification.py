from typing import Literal

from pydantic import BaseModel


class NotificationIn(BaseModel): kind: Literal["sms","email"]; recipient: str; subject: str = "响帮帮无忧保通知"; content: str; template: str = "general"
