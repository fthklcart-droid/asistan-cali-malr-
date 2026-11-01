"""Yerel köprüye kimlik doğrulamalı istekler (masaüstü arayüzü, ses ve beceri modülleri kullanır)."""
from __future__ import annotations

import requests

from . import device
from .config import base_url


class ApiError(RuntimeError):
    def __init__(self, message: str, status_code: int = 0):
        super().__init__(message)
        self.status_code = status_code


def _detail(r: requests.Response) -> str:
    try:
        detail = r.json().get("detail", r.text)
    except ValueError:
        return r.text[:300]
    if isinstance(detail, list):  # pydantic doğrulama hatası
        return "; ".join(f"{'.'.join(map(str, d.get('loc', [])[1:]))}: {d.get('msg')}" for d in detail)
    return str(detail)


def request(method: str, path: str, json=None, timeout: float = 10, params=None):
    try:
        r = requests.request(method, base_url() + path, json=json, params=params, timeout=timeout,
                             headers=device.auth_headers())
    except requests.ConnectionError as exc:
        raise ApiError("Yerel köprüye ulaşılamadı. Uygulamayı run_windows.bat ile yeniden başlatın.") from exc
    if r.status_code >= 400:
        raise ApiError(_detail(r), r.status_code)
    return r.json() if r.content else None


def get(path: str, timeout: float = 5, params=None):
    return request("GET", path, timeout=timeout, params=params)


def post(path: str, json=None, timeout: float = 10):
    return request("POST", path, json=json, timeout=timeout)


def put(path: str, json=None, timeout: float = 10):
    return request("PUT", path, json=json, timeout=timeout)


def delete(path: str, timeout: float = 10):
    return request("DELETE", path, timeout=timeout)
