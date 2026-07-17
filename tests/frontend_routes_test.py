"""Every Vue route must be in the backend's SPA whitelist.

`backend/app.py` serves the SPA from an explicit allowlist rather than a
wildcard fallback (a wildcard would make /data.db return index.html instead of
404). The cost is that a Vue route missing from the list 404s on direct open and
on refresh — the page works when you navigate to it in-app and breaks the moment
a user bookmarks it. That bit the usage-lock task; this test makes it structural
rather than a thing to remember.
"""
import os
import re
import sys
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "routes-test")
os.environ.setdefault("DATABASE_URL", "sqlite:///:memory:")

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.app import _FRONTEND_ROUTES


def run() -> None:
    routes_ts = (ROOT / "web" / "src" / "router" / "routes.ts").read_text()
    # Only top-level paths; nested/param routes are served by their parent.
    declared = {
        match.group(1)
        for match in re.finditer(r"path:\s*'(/[^']*)'", routes_ts)
        if ":" not in match.group(1)
    }

    missing = sorted(p for p in declared if p not in _FRONTEND_ROUTES)
    assert not missing, (
        f"这些 Vue 路由不在 backend/app.py 的 _FRONTEND_ROUTES 白名单里，"
        f"直接打开或刷新会 404：{missing}"
    )
    print(f"  {len(declared)} 个 Vue 路由全部在 SPA 白名单中")
    print("frontend routes test passed")


if __name__ == "__main__":
    run()
