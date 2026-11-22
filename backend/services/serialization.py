# story-p3-compliance：证件号派生列（hash/密文）绝不进任何序列化出口——
# 它们只服务于查重与解密，泄漏 hash 等于泄漏可离线碰撞的指纹。
_EXCLUDED_COLUMNS = {"id_number_hash", "id_number_cipher"}


def serialize(obj):
    return {c.name: getattr(obj, c.name) for c in obj.__table__.columns
            if c.name not in _EXCLUDED_COLUMNS}

def amount(value:float) -> float:
    return round(float(value or 0),2)
