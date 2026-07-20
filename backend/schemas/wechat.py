from pydantic import BaseModel


class WeChatBindOpenidIn(BaseModel):
    code: str
