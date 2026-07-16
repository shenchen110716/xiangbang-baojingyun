"""Identity protection primitives for v4.2 Phase 2 (SYSTEM-DESIGN-V4.2 §6.4).

Matching needs a stable, non-reversible fingerprint; storage needs reversible
ciphertext; every response and log line needs a masked form. These three are
deliberately different functions — the hash must never be used as storage and
the ciphertext must never be used for matching.
"""
import os
import sys

os.environ.setdefault("ID_ENCRYPTION_KEY", "x" * 44)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.core.id_number import id_encrypt, id_decrypt, id_hash, mask_id_number

RAW = "340123199001011234"


def test_hash_is_deterministic_and_not_reversible():
    assert id_hash(RAW) == id_hash(RAW)
    assert id_hash(RAW) != id_hash("340123199001011235")
    assert RAW not in id_hash(RAW)
    assert len(id_hash(RAW)) == 64


def test_hash_normalises_case_and_whitespace():
    assert id_hash(" 34012319900101123x ") == id_hash("34012319900101123X")


def test_encrypt_roundtrips_and_is_not_deterministic():
    a, b = id_encrypt(RAW), id_encrypt(RAW)
    assert a != b                      # Fernet 含随机 IV
    assert id_decrypt(a) == RAW and id_decrypt(b) == RAW
    assert RAW not in a


def test_mask_hides_the_birth_date_segment():
    assert mask_id_number(RAW) == "340123********1234"
    assert mask_id_number("") == ""
    assert mask_id_number("123") == "***"


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"  {name} ok")
    print("id_number tests passed")
