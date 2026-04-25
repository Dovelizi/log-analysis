# -*- coding: utf-8 -*-
"""
腾讯云 TC3-HMAC-SHA256 签名模块
严格遵循官方文档: https://cloud.tencent.com/document/api/614/56474

此模块独立实现签名逻辑，可被其他模块复用。
"""

import hashlib
import hmac
import json
import logging
import time
from datetime import datetime
from typing import Tuple, Optional

logger = logging.getLogger(__name__)

# 配置日志级别，确保 debug 日志能输出
logging.basicConfig(level=logging.DEBUG, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')


def sign(key: bytes, msg: str) -> bytes:
    """
    计算签名摘要函数
    
    Args:
        key: 密钥（字节类型）
        msg: 待签名消息（字符串）
    
    Returns:
        HMAC-SHA256 摘要（字节类型）
    """
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def generate_tc3_authorization(
    secret_id: str,
    secret_key: str,
    service: str,
    host: str,
    action: str,
    params: dict,
    timestamp: Optional[int] = None
) -> Tuple[str, int, str]:
    """
    生成 TC3-HMAC-SHA256 签名
    严格按照腾讯云官方 Python 示例代码实现
    
    Args:
        secret_id: 密钥ID
        secret_key: 密钥Key
        service: 服务名称，如 cls
        host: 请求域名
        action: 操作名称
        params: 请求参数
        timestamp: 时间戳（可选，默认当前时间）
    
    Returns:
        (authorization, timestamp, payload) 元组
        - authorization: 完整的 Authorization 头部值
        - timestamp: 使用的时间戳
        - payload: JSON 格式的请求体
    """
    algorithm = "TC3-HMAC-SHA256"
    
    if timestamp is None:
        timestamp = int(time.time())
    
    # 使用 UTC 时间计算日期
    date = datetime.utcfromtimestamp(timestamp).strftime("%Y-%m-%d")
    payload = json.dumps(params)
    
    # ============ 步骤 1：拼接规范请求串 CanonicalRequest ============
    http_request_method = "POST"
    canonical_uri = "/"
    canonical_querystring = ""
    
    # Content-Type 必须与实际请求一致
    ct = "application/json; charset=utf-8"
    # 请求头按字母顺序排列，且小写
    canonical_headers = "content-type:%s\nhost:%s\nx-tc-action:%s\n" % (ct, host, action.lower())
    signed_headers = "content-type;host;x-tc-action"
    
    # 对请求体做 SHA256 哈希
    hashed_request_payload = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    
    canonical_request = (
        http_request_method + "\n" +
        canonical_uri + "\n" +
        canonical_querystring + "\n" +
        canonical_headers + "\n" +
        signed_headers + "\n" +
        hashed_request_payload
    )
    logger.debug(f"canonical_request=========> {canonical_request}")
    
    # ============ 步骤 2：拼接待签名字符串 StringToSign ============
    credential_scope = date + "/" + service + "/" + "tc3_request"
    hashed_canonical_request = hashlib.sha256(canonical_request.encode("utf-8")).hexdigest()
    
    string_to_sign = (
        algorithm + "\n" +
        str(timestamp) + "\n" +
        credential_scope + "\n" +
        hashed_canonical_request
    )
    logger.debug(f"string_to_sign=========> {string_to_sign}")

    # ============ 步骤 3：计算签名 Signature ============
    secret_date = sign(("TC3" + secret_key).encode("utf-8"), date)
    secret_service = sign(secret_date, service)
    secret_signing = sign(secret_service, "tc3_request")
    signature = hmac.new(secret_signing, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    logger.debug(f"signature=========> {signature}")

    # ============ 步骤 4：拼接 Authorization ============
    authorization = (
        algorithm + " " +
        "Credential=" + secret_id + "/" + credential_scope + ", " +
        "SignedHeaders=" + signed_headers + ", " +
        "Signature=" + signature
    )
    logger.debug(f"authorization=========> {authorization}")
    
    return authorization, timestamp, payload
