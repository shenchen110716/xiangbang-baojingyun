"""Shared CSV/XLSX import reading.

Extracted from `backend/routers/insured.py` so the employment-fact import can
reuse the exact same size guard and parsing rules. Lives in services rather
than routers because services must not import routers (routers already import
services, which would be a cycle).
"""
import csv
import io
from datetime import datetime

import openpyxl
from fastapi import HTTPException

MAX_IMPORT_FILE_BYTES = 10 * 1024 * 1024


def read_import_rows(content: bytes, filename: str) -> list[list[str]]:
    if len(content) > MAX_IMPORT_FILE_BYTES:
        raise HTTPException(413, '单个导入文件不能超过 10MB，请拆分后重试')
    name = (filename or '').lower()
    try:
        if name.endswith('.xlsx'):
            book = openpyxl.load_workbook(io.BytesIO(content), read_only=True, data_only=True)
            try:
                sheet = book.active
                raw = [[value.isoformat(sep=' ') if isinstance(value, datetime) else str(value).strip() if value is not None else '' for value in row] for row in sheet.iter_rows(values_only=True)]
            finally:
                book.close()
        elif name.endswith('.csv'):
            raw = [[str(value).strip() for value in row] for row in csv.reader(io.StringIO(content.decode('utf-8-sig')))]
        else:
            raise HTTPException(400, '仅支持 CSV 或 XLSX 电子表格')
    except HTTPException:
        raise
    except UnicodeDecodeError as exc:
        raise HTTPException(400, 'CSV 文件必须使用 UTF-8 编码，建议使用系统标准模板') from exc
    except Exception as exc:
        raise HTTPException(400, f'电子表格解析失败：{exc}') from exc
    return [row for row in raw if any(value.strip() for value in row)]
