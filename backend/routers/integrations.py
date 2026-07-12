import os

from fastapi import APIRouter, Depends

from ..core.security import current_user
from ..models import User

router = APIRouter(prefix="/api", tags=["integrations"])


@router.get("/providers/status")
def provider_status(user: User = Depends(current_user)):
    return {"mode": os.getenv("INTEGRATION_MODE", "mock"), "insurer_api": bool(os.getenv("INSURER_API_BASE_URL")), "sms": bool(os.getenv("SMS_PROVIDER_URL")), "email": bool(os.getenv("SMTP_HOST")), "payment": bool(os.getenv("PAYMENT_PROVIDER_URL"))}
