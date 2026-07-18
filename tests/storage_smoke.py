"""core/storage 冒烟：本地磁盘 roundtrip + S3 兼容后端（mock 客户端）。

不需要真实 R2/S3 凭据：S3 分支用假客户端验证 put/presign/delete 被正确调用、
以及 url 标记（s3://key）与下载重定向逻辑。本地分支验证 save→resolve→delete。
"""
import io
import os
import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from backend.core import storage
from backend.core.config import ROOT


def test_local_roundtrip():
    storage.STORAGE_BACKEND = "local"
    key = "claims/999/smoke_ab12.txt"
    url = storage.save_bytes(key, b"hello-local")
    assert url == "/uploads/" + key
    assert (ROOT / "uploads" / key).read_bytes() == b"hello-local"

    resolved = storage.resolve(url)
    assert resolved is not None and resolved[0] == "file"
    assert resolved[1] == ROOT / "uploads" / key

    # fileobj 变体
    url2 = storage.save_fileobj("claims/999/smoke_cd34.txt", io.BytesIO(b"stream-bytes"))
    assert storage.resolve(url2)[0] == "file"

    storage.delete(url)
    storage.delete(url2)
    assert storage.resolve(url) is None  # 删除后缺失
    print("local roundtrip OK")


def test_missing_local_returns_none():
    storage.STORAGE_BACKEND = "local"
    assert storage.resolve("/uploads/claims/does/not/exist.jpg") is None
    print("missing local -> None OK")


def test_http_url_passthrough():
    assert storage.resolve("https://cdn.example.com/x.pdf") == ("redirect", "https://cdn.example.com/x.pdf")
    print("http passthrough OK")


class _FakeS3:
    def __init__(self):
        self.objects = {}
        self.deleted = []
    def put_object(self, Bucket, Key, Body):
        self.objects[Key] = Body
    def upload_fileobj(self, fileobj, Bucket, Key):
        self.objects[Key] = fileobj.read()
    def generate_presigned_url(self, op, Params, ExpiresIn):
        assert op == "get_object"
        return f"https://signed.example/{Params['Key']}?ttl={ExpiresIn}"
    def delete_object(self, Bucket, Key):
        self.deleted.append(Key)


def test_s3_backend():
    fake = _FakeS3()
    # 切到 s3 后端并注入假客户端
    storage.STORAGE_BACKEND = "s3"
    storage.S3_BUCKET = "test-bucket"
    storage.S3_ENDPOINT_URL = "https://acct.r2.cloudflarestorage.com"
    storage._client = fake

    url = storage.save_bytes("claims/2/xy99.jpg", b"img-bytes")
    assert url == "s3://claims/2/xy99.jpg"
    assert fake.objects["claims/2/xy99.jpg"] == b"img-bytes"

    url2 = storage.save_fileobj("positions/5/vid.mp4", io.BytesIO(b"video-bytes"))
    assert url2 == "s3://positions/5/vid.mp4"
    assert fake.objects["positions/5/vid.mp4"] == b"video-bytes"

    kind, href = storage.resolve(url)
    assert kind == "redirect" and href.startswith("https://signed.example/claims/2/xy99.jpg")

    # 带文件名下载（保单）走 ResponseContentDisposition
    kind2, href2 = storage.resolve("s3://policies/3/doc.pdf", filename="保单.pdf")
    assert kind2 == "redirect"

    storage.delete(url)
    assert "claims/2/xy99.jpg" in fake.deleted

    # 复位，避免影响其他测试
    storage.STORAGE_BACKEND = "local"
    storage._client = None
    print("s3 backend (mock) OK")


def run():
    test_local_roundtrip()
    test_missing_local_returns_none()
    test_http_url_passthrough()
    test_s3_backend()
    print("storage_smoke: ALL GREEN")


if __name__ == "__main__":
    run()
