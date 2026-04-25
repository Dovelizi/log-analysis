#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Python vs Java 版 logAnalysis 接口对比工具。

用法:
    python3 diff_py_vs_java.py \
        --python-url http://127.0.0.1:8080 \
        --java-url   http://127.0.0.1:8081 \
        [--only /api/dashboard/overview] \
        [--verbose]

输出:
    - 每个接口：PASS / DIFF / FAIL
    - DIFF 明细：结构差异（缺失/多余 key）+ 值差异（类型不一致/数值偏差>容差）
    - 末尾汇总

退出码:
    0  全部 PASS
    1  有 DIFF
    2  有 FAIL（网络 / 非 200）

不会调用任何写接口。默认只比对纯读的 GET 请求。
"""
import argparse
import json
import sys
import time
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from urllib import request as urlrequest
from urllib.error import HTTPError, URLError

# ==================== 配置 ====================

# 只读路由清单（这些接口不写库，适合对比）
READ_ONLY_CASES: List[Dict[str, Any]] = [
    # --- 健康检查 ---
    {"name": "health", "method": "GET", "path": "/api/health"},

    # --- 凭证/Topic/配置 CRUD 的 GET 列表 ---
    {"name": "credentials_list", "method": "GET", "path": "/api/credentials"},
    {"name": "topics_list",      "method": "GET", "path": "/api/topics"},
    {"name": "query_configs",    "method": "GET", "path": "/api/query-configs"},

    # --- Dashboard 只读（用当天） ---
    {"name": "dashboard_available_dates", "method": "GET", "path": "/api/dashboard/available-dates"},
    {"name": "dashboard_overview",        "method": "GET", "path": "/api/dashboard/overview"},
    {"name": "dashboard_control_stats",   "method": "GET", "path": "/api/dashboard/control-hitch/statistics"},
    {"name": "dashboard_gw_stats",        "method": "GET", "path": "/api/dashboard/gw-hitch/statistics"},
    {"name": "dashboard_cost_time_stats", "method": "GET", "path": "/api/dashboard/hitch-control-cost-time/statistics"},
    {"name": "dashboard_supplier_sp",     "method": "GET", "path": "/api/dashboard/hitch-supplier-error-sp/statistics"},
    {"name": "dashboard_supplier_total",  "method": "GET", "path": "/api/dashboard/hitch-supplier-error-total/statistics"},

    # --- GW / Control Hitch 只读 ---
    {"name": "gw_hitch_data",          "method": "GET", "path": "/api/gw-hitch/data"},
    {"name": "gw_hitch_schema",        "method": "GET", "path": "/api/gw-hitch/schema"},
    {"name": "gw_hitch_transform_rules","method": "GET", "path": "/api/gw-hitch/transform-rules"},
    {"name": "gw_hitch_processor_types","method": "GET", "path": "/api/gw-hitch/processor-types"},
    {"name": "control_hitch_data",     "method": "GET", "path": "/api/control-hitch/data"},
    {"name": "control_hitch_schema",   "method": "GET", "path": "/api/control-hitch/schema"},

    # --- Table Mapping 只读 ---
    {"name": "table_mappings",           "method": "GET", "path": "/api/table-mappings"},
    {"name": "table_mapping_field_types","method": "GET", "path": "/api/table-mappings/field-types"},
    {"name": "table_mapping_filter_ops", "method": "GET", "path": "/api/table-mappings/filter-operators"},

    # --- Scheduler 状态 ---
    {"name": "scheduler_status",      "method": "GET", "path": "/api/scheduler/status"},
    {"name": "scheduler_push_status", "method": "GET", "path": "/api/scheduler/push-status"},

    # --- Report 只读 ---
    {"name": "report_push_configs",  "method": "GET", "path": "/api/report/push-configs"},
    {"name": "report_summary",       "method": "GET", "path": "/api/report/summary"},
    {"name": "report_weekly_errors", "method": "GET", "path": "/api/report/weekly-new-errors"},
    {"name": "report_push_logs",     "method": "GET", "path": "/api/report/push-logs"},

    # --- 统计 / 分析结果 ---
    {"name": "statistics",       "method": "GET", "path": "/api/statistics"},
    {"name": "analysis_results", "method": "GET", "path": "/api/analysis-results"},
    {"name": "log_records",      "method": "GET", "path": "/api/log-records", "params": {"limit": 5}},
]

# 允许两侧不同的字段路径（正则也可以，这里用简单字符串 contains）
IGNORE_PATHS = {
    "timestamp", "generated_at",
    "created_at", "updated_at", "update_time", "create_time",
    "last_push_time", "last_scheduled_push_time", "last_execution",
    "_debug", "_debug_params",
    # Health 里具体的 checks 值（Redis 连接有时间差）
    "checks.database", "checks.redis",
    # Dashboard 的 trend_hourly 顺序可能不一致（Python 行为差异，SUM 后 ORDER BY time_bucket）
}

# 数值比较容差（相对差 <=1% 算一致）
NUM_TOLERANCE = 0.01


# ==================== HTTP 客户端 ====================

def http_get(url: str, timeout: float = 30.0) -> Tuple[int, Any]:
    try:
        req = urlrequest.Request(url, method="GET")
        req.add_header("Accept", "application/json")
        with urlrequest.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            status = resp.status
            try:
                return status, json.loads(body)
            except json.JSONDecodeError:
                return status, body
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, body
    except URLError as e:
        return 0, f"URLError: {e}"
    except Exception as e:
        return 0, f"Exception: {type(e).__name__}: {e}"


# ==================== diff 核心 ====================

class DiffReport:
    def __init__(self):
        self.missing_in_java: List[str] = []   # Python 有 Java 没
        self.extra_in_java: List[str] = []     # Java 有 Python 没
        self.value_diffs: List[Dict[str, Any]] = []
        self.type_diffs: List[Dict[str, Any]] = []

    @property
    def has_diff(self) -> bool:
        return bool(self.missing_in_java or self.extra_in_java
                    or self.value_diffs or self.type_diffs)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "missing_in_java": self.missing_in_java,
            "extra_in_java": self.extra_in_java,
            "value_diffs": self.value_diffs,
            "type_diffs": self.type_diffs,
        }


def is_ignored(path: str) -> bool:
    for ign in IGNORE_PATHS:
        if ign in path:
            return True
    return False


def numbers_close(a: Any, b: Any) -> bool:
    try:
        af = float(a)
        bf = float(b)
        if af == bf:
            return True
        if af == 0 or bf == 0:
            return abs(af - bf) < 1e-9
        return abs(af - bf) / max(abs(af), abs(bf)) <= NUM_TOLERANCE
    except (TypeError, ValueError):
        return False


def _type_name(v: Any) -> str:
    if v is None: return "null"
    if isinstance(v, bool): return "bool"
    if isinstance(v, int): return "int"
    if isinstance(v, float): return "float"
    if isinstance(v, str): return "str"
    if isinstance(v, list): return "list"
    if isinstance(v, dict): return "dict"
    return type(v).__name__


def diff_value(py: Any, jv: Any, path: str, report: DiffReport) -> None:
    if is_ignored(path):
        return

    # 类型对比（宽松：int/float 都算 number；null 和 空 list/dict 宽松）
    py_t = _type_name(py)
    jv_t = _type_name(jv)

    # null vs 空集合：双向宽松
    if py is None and jv in ([], {}, ""):
        return
    if jv is None and py in ([], {}, ""):
        return

    # 数值类型
    py_is_num = isinstance(py, (int, float)) and not isinstance(py, bool)
    jv_is_num = isinstance(jv, (int, float)) and not isinstance(jv, bool)
    if py_is_num and jv_is_num:
        if not numbers_close(py, jv):
            report.value_diffs.append({"path": path, "py": py, "java": jv})
        return

    if py_t != jv_t:
        report.type_diffs.append({"path": path, "py_type": py_t, "java_type": jv_t,
                                  "py_sample": _sample(py), "java_sample": _sample(jv)})
        return

    # 基本类型相等
    if not isinstance(py, (dict, list)):
        if py != jv:
            report.value_diffs.append({"path": path, "py": _sample(py), "java": _sample(jv)})
        return

    # dict
    if isinstance(py, dict):
        for k in py:
            sub_path = f"{path}.{k}" if path else k
            if k not in jv:
                if not is_ignored(sub_path):
                    report.missing_in_java.append(sub_path)
            else:
                diff_value(py[k], jv[k], sub_path, report)
        for k in jv:
            sub_path = f"{path}.{k}" if path else k
            if k not in py and not is_ignored(sub_path):
                report.extra_in_java.append(sub_path)
        return

    # list：长度差异记录；同长度下逐项比
    if isinstance(py, list):
        if len(py) != len(jv):
            report.value_diffs.append({
                "path": f"{path} (list length)",
                "py": len(py),
                "java": len(jv),
            })
            # 继续比较共同前缀
            n = min(len(py), len(jv))
            for i in range(n):
                diff_value(py[i], jv[i], f"{path}[{i}]", report)
        else:
            for i, (a, b) in enumerate(zip(py, jv)):
                diff_value(a, b, f"{path}[{i}]", report)


def _sample(v: Any, limit: int = 80) -> Any:
    """把值压成短字符串用于报告"""
    s = json.dumps(v, ensure_ascii=False, default=str) if not isinstance(v, str) else v
    return s if len(s) <= limit else s[:limit] + "..."


# ==================== 运行单个用例 ====================

def run_case(case: Dict[str, Any], py_url: str, java_url: str) -> Dict[str, Any]:
    name = case["name"]
    path = case["path"]
    params = case.get("params") or {}
    qs = ""
    if params:
        qs = "?" + "&".join(f"{k}={v}" for k, v in params.items())

    py_status, py_body = http_get(py_url + path + qs)
    java_status, java_body = http_get(java_url + path + qs)

    result = {
        "name": name,
        "path": path,
        "py_status": py_status,
        "java_status": java_status,
    }

    # 网络或非 200 视为 FAIL
    if py_status == 0 or java_status == 0:
        result["status"] = "FAIL"
        result["message"] = f"网络错误 py={py_body!r} java={java_body!r}"
        return result
    if py_status != java_status:
        result["status"] = "DIFF"
        result["message"] = f"HTTP 状态不一致 py={py_status} java={java_status}"
        return result
    if py_status != 200:
        result["status"] = "DIFF"
        result["message"] = f"两侧都是 {py_status}"
        result["py_body"] = _sample(py_body)
        result["java_body"] = _sample(java_body)
        return result

    report = DiffReport()
    diff_value(py_body, java_body, "", report)
    if report.has_diff:
        result["status"] = "DIFF"
        result["diff"] = report.to_dict()
    else:
        result["status"] = "PASS"
    return result


# ==================== 主入口 ====================

def main() -> int:
    ap = argparse.ArgumentParser(description="Python vs Java logAnalysis API diff")
    ap.add_argument("--python-url", required=True, help="Python 服务 base url, e.g. http://127.0.0.1:8080")
    ap.add_argument("--java-url",   required=True, help="Java 服务 base url, e.g. http://127.0.0.1:8081")
    ap.add_argument("--only", default=None, help="只跑这一个路径，如 /api/health")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--output", default=None, help="可选：把完整报告写入 JSON 文件")
    args = ap.parse_args()

    cases = [c for c in READ_ONLY_CASES
             if args.only is None or c["path"] == args.only]
    if not cases:
        print(f"[!] 未找到匹配 --only={args.only} 的用例", file=sys.stderr)
        return 2

    print(f"=== Python: {args.python_url}  Java: {args.java_url}  cases: {len(cases)} ===")
    print()

    results = []
    pass_n = diff_n = fail_n = 0
    started = time.time()

    for case in cases:
        r = run_case(case, args.python_url, args.java_url)
        results.append(r)
        mark = {"PASS": "✅", "DIFF": "⚠️ ", "FAIL": "❌"}.get(r["status"], "?")
        print(f"{mark}  {r['name']:40s}  {r['path']}")
        if r["status"] == "PASS":
            pass_n += 1
        elif r["status"] == "DIFF":
            diff_n += 1
            if "message" in r:
                print(f"     {r['message']}")
            if "diff" in r:
                d = r["diff"]
                if d["missing_in_java"]:
                    print(f"     missing_in_java: {d['missing_in_java'][:5]}"
                          + (f" ... (+{len(d['missing_in_java']) - 5} 更多)" if len(d['missing_in_java']) > 5 else ""))
                if d["extra_in_java"]:
                    print(f"     extra_in_java:   {d['extra_in_java'][:5]}"
                          + (f" ... (+{len(d['extra_in_java']) - 5} 更多)" if len(d['extra_in_java']) > 5 else ""))
                if d["type_diffs"]:
                    for t in d["type_diffs"][:3]:
                        print(f"     type_diff: {t['path']}  py={t['py_type']} java={t['java_type']}")
                    if len(d["type_diffs"]) > 3:
                        print(f"     ... (+{len(d['type_diffs']) - 3} 更多 type_diffs)")
                if d["value_diffs"]:
                    for v in d["value_diffs"][:5]:
                        print(f"     value_diff: {v['path']}  py={v['py']!r}  java={v['java']!r}")
                    if len(d["value_diffs"]) > 5:
                        print(f"     ... (+{len(d['value_diffs']) - 5} 更多 value_diffs)")
        else:
            fail_n += 1
            print(f"     {r.get('message', '')}")
            if args.verbose:
                print(f"     py_body = {r.get('py_body', _sample(r.get('py_status')))}")
                print(f"     java_body = {r.get('java_body', _sample(r.get('java_status')))}")

    elapsed = time.time() - started
    print()
    print("=" * 60)
    print(f"合计: {len(cases)}   ✅ PASS={pass_n}   ⚠️  DIFF={diff_n}   ❌ FAIL={fail_n}   耗时 {elapsed:.1f}s")

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump({
                "python_url": args.python_url,
                "java_url": args.java_url,
                "generated_at": datetime.now().isoformat(),
                "summary": {"pass": pass_n, "diff": diff_n, "fail": fail_n, "total": len(cases)},
                "results": results,
            }, f, ensure_ascii=False, indent=2, default=str)
        print(f"完整报告已写入: {args.output}")

    if fail_n:
        return 2
    if diff_n:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
