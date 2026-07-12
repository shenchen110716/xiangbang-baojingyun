from datetime import datetime, timezone

from fastapi import APIRouter

router = APIRouter(prefix="/api", tags=["health"])


@router.get("/health")
def health(): return {"ok": True, "service": "xiangbangbaojingyun", "time": datetime.now(timezone.utc).isoformat()}
