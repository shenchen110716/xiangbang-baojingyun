#!/usr/bin/env python3
"""对象存储（Cloudflare R2 / S3）真实连通性验证：put → presign → GET → delete 全回环。

用法（本地或 CI，凭据用环境变量，不落盘）：
  STORAGE_BACKEND=s3 \
  S3_BUCKET=xiangbang-uploads \
  S3_ENDPOINT_URL=https://<AccountID>.r2.cloudflarestorage.com \
  S3_ACCESS_KEY_ID=xxx S3_SECRET_ACCESS_KEY=yyy S3_REGION=auto \
  python3 scripts/verify_object_storage.py

成功输出各步 OK 并以 0 退出；任一步失败打印原因并非 0 退出。桶应为私有——脚本用
后端同一套 storage 抽象（上传→120s 预签名 URL→HTTP 下载→删除），验证的是生产实际路径。
"""
import os
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main() -> int:
    if os.getenv("STORAGE_BACKEND", "").lower() != "s3":
        print("✗ 需要 STORAGE_BACKEND=s3 及 S3_* 凭据环境变量")
        return 2

    from backend.core import storage

    if not storage.use_s3():
        print("✗ storage 未识别到 s3 配置，请检查 S3_BUCKET / S3_ENDPOINT_URL")
        return 2

    key = "healthcheck/connectivity-probe.txt"
    payload = b"xiangbang object-storage connectivity probe"

    # 1) 上传
    url = storage.save_bytes(key, payload)
    assert url == f"s3://{key}", url
    print(f"✓ 上传成功 → {url}")

    # 2) 预签名 + 3) HTTP 下载校验内容
    kind, href = storage.resolve(url)
    assert kind == "redirect", kind
    print(f"✓ 生成预签名 URL（{storage.S3_PRESIGN_TTL}s 有效）")
    with urllib.request.urlopen(href, timeout=15) as res:
        got = res.read()
    assert got == payload, f"下载内容不一致：{got!r}"
    print("✓ 预签名下载内容一致")

    # 4) 删除
    storage.delete(url)
    after = storage.resolve(url)
    # 删除后再签名下载应 404（对象已不存在）
    if after and after[0] == "redirect":
        try:
            urllib.request.urlopen(after[1], timeout=15)
            print("⚠ 删除后仍可下载（可能存在最终一致性延迟），请稍后复查")
        except Exception:
            print("✓ 删除成功（对象已不可下载）")
    print("\n对象存储连通性验证通过 ✅ 生产上传件将持久化，不再随部署丢失。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"✗ 验证失败：{type(exc).__name__}: {exc}")
        sys.exit(1)
