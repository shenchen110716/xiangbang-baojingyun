"""统一文件存储抽象：本地磁盘（开发）与 S3 兼容对象存储（生产，如 Cloudflare R2）。

背景：Render 容器磁盘是临时的，每次部署/休眠都会清空 `uploads/`，导致理赔材料、
岗位视频、保单文件等上传件丢失（数据库记录仍在 → “文件不存在”）。生产改用私有对象
存储：上传写入私有桶，下载仍经后端短时签名校验后，再用 **120s 预签名 URL 重定向**，
桶不公开，符合“敏感材料不静态挂载、只短时签名下载”的安全姿态。

切换由环境变量控制，默认 local，本地开发与测试行为不变：
  STORAGE_BACKEND=s3
  S3_BUCKET=<bucket>
  S3_ENDPOINT_URL=https://<accountid>.r2.cloudflarestorage.com
  S3_ACCESS_KEY_ID / S3_SECRET_ACCESS_KEY
  S3_REGION=auto            # R2 用 auto
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import BinaryIO, Optional, Tuple

from .config import ROOT

S3_PRESIGN_TTL = int(os.getenv("S3_PRESIGN_TTL", "120"))

_UPLOADS = ROOT / "uploads"
_client = None
# 测试可直接改这个覆盖后端；生产由平台端「系统设置」/环境变量决定。
STORAGE_BACKEND = None


def _cfg(key: str, default: str = "") -> str:
    """配置读取：平台端系统设置（DB，密钥自动解密）→ 环境变量 → 默认值。
    延迟导入 services.settings，避免 core→services 的导入期循环。"""
    if key == "STORAGE_BACKEND" and STORAGE_BACKEND is not None:
        return STORAGE_BACKEND  # 测试覆盖
    try:
        from ..services import settings as settings_service
        return settings_service.get(key, default)
    except Exception:
        return os.getenv(key, default)


def use_s3() -> bool:
    return _cfg("STORAGE_BACKEND", "local").lower() == "s3" and bool(_cfg("S3_BUCKET")) and bool(_cfg("S3_ENDPOINT_URL"))


def _s3():
    global _client
    if _client is None:
        import boto3
        from botocore.config import Config

        _client = boto3.client(
            "s3",
            endpoint_url=_cfg("S3_ENDPOINT_URL"),
            aws_access_key_id=_cfg("S3_ACCESS_KEY_ID"),
            aws_secret_access_key=_cfg("S3_SECRET_ACCESS_KEY"),
            region_name=_cfg("S3_REGION", "auto"),
            config=Config(signature_version="s3v4"),
        )
    return _client


def _bucket() -> str:
    return _cfg("S3_BUCKET")


def save_bytes(key: str, content: bytes) -> str:
    """保存字节内容。key 形如 'claims/2/ab12cd.jpg'，返回持久化到数据库的 url 标记。"""
    if use_s3():
        _s3().put_object(Bucket=_bucket(), Key=key, Body=content)
        return f"s3://{key}"
    path = _UPLOADS / key
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return f"/uploads/{key}"


def save_fileobj(key: str, fileobj: BinaryIO) -> str:
    """保存可读文件对象（用于大文件如岗位视频，避免整体载入内存）。fileobj 需可从头读取。"""
    fileobj.seek(0)
    if use_s3():
        _s3().upload_fileobj(fileobj, _bucket(), key)
        return f"s3://{key}"
    path = _UPLOADS / key
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as out:
        while chunk := fileobj.read(1024 * 1024):
            out.write(chunk)
    return f"/uploads/{key}"


def delete(url: str) -> None:
    """删除已存储的文件（本地磁盘或对象存储）。外链 http(s) 不处理。幂等，忽略缺失。"""
    if url.startswith("http://") or url.startswith("https://"):
        return
    if url.startswith("s3://"):
        try:
            _s3().delete_object(Bucket=_bucket(), Key=url[len("s3://"):])
        except Exception:
            pass
        return
    path = ROOT / url.lstrip("/")
    if path.is_file():
        path.unlink()


def resolve(url: str, filename: Optional[str] = None) -> Optional[Tuple[str, object]]:
    """把持久化的 url 标记解析为下载来源：
    - ('redirect', href) —— 直接重定向（外链 http(s) 或对象存储预签名 URL）
    - ('file', Path)     —— 本地文件
    - None               —— 文件缺失
    """
    if url.startswith("http://") or url.startswith("https://"):
        return ("redirect", url)
    if url.startswith("s3://"):
        key = url[len("s3://"):]
        params = {"Bucket": _bucket(), "Key": key}
        if filename:
            params["ResponseContentDisposition"] = f'attachment; filename="{filename}"'
        href = _s3().generate_presigned_url("get_object", Params=params, ExpiresIn=S3_PRESIGN_TTL)
        return ("redirect", href)
    path = ROOT / url.lstrip("/")
    return ("file", path) if path.is_file() else None
