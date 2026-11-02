#!/usr/bin/env python3
"""
MDT 账号迁移 CLI（macOS / Windows 通用）

用法：
  python3 migrate-account.py --server-dir /path/to/mdtserver --account-id 123 --new-qq 123456789
  python3 migrate-account.py --server-dir E:\\mdtdo\\mdtserver --account-id 123 --check

原理：
  只写 config/stats/account-migrate-requests.jsonl；
  运行中的游戏服 accountMigrate.kts 轮询请求，在游戏进程里用数据库事务改名；
  工具再读 account-migrate-results.jsonl。不需要停服，不直接打开 H2 文件。
"""

import argparse
import json
import os
import sys
import time
import uuid
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def fail(message: str, code: int = 1):
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(code)


def main():
    parser = argparse.ArgumentParser(description="MDT 账号运行中迁移工具")
    parser.add_argument("--server-dir", default=os.environ.get("MDT_SERVER_DIR", ""),
                        help="mdtserver 目录；也可用环境变量 MDT_SERVER_DIR")
    parser.add_argument("--account-id", type=int, required=True, help="账号 ID，例如 123")
    parser.add_argument("--new-qq", default="", help="新 QQ 号；--check 时可省略")
    parser.add_argument("--check", action="store_true", help="只查询当前用户名，不修改")
    parser.add_argument("--clear-email", action="store_true",
                        help="同时清除旧邮箱绑定（旧邮箱与目标 QQ 不一致时使用）")
    parser.add_argument("--timeout", type=int, default=15, help="等待游戏服处理秒数，默认 15")
    args = parser.parse_args()

    if not args.server_dir:
        fail("缺少 --server-dir（或设置 MDT_SERVER_DIR）")
    root = Path(args.server_dir).expanduser().resolve()
    if not root.is_dir():
        fail(f"目录不存在：{root}")

    stats = root / "config" / "stats"
    stats.mkdir(parents=True, exist_ok=True)
    req_path = stats / "account-migrate-requests.jsonl"
    res_path = stats / "account-migrate-results.jsonl"

    if not args.check:
        new_qq = args.new_qq.strip()
        if not (new_qq.isdigit() and 5 <= len(new_qq) <= 12 and not new_qq.startswith("0")):
            fail("--new-qq 必须是 5-12 位数字，且不能以 0 开头")
    else:
        new_qq = args.new_qq.strip()

    request_id = uuid.uuid4().hex
    payload = {
        "id": request_id,
        "action": "check" if args.check else "rename",
        "accountId": args.account_id,
        "newQq": new_qq,
        "clearEmail": bool(args.clear_email),
        "requestedAt": int(time.time() * 1000),
    }
    line = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"

    # 先读旧结果，避免误读历史记录
    deadline = time.time() + args.timeout
    with req_path.open("a", encoding="utf-8", newline="\n") as f:
        f.write(line)
    try:
        os.chmod(req_path, 0o600)
    except OSError:
        pass

    print(f"[INFO] 已提交迁移请求 {request_id}")
    print(f"[INFO] 等待游戏服处理（{args.timeout}s）...")

    result = None
    while time.time() < deadline:
        if res_path.exists():
            try:
                for raw in res_path.read_text(encoding="utf-8", errors="replace").splitlines():
                    if not raw.strip():
                        continue
                    try:
                        item = json.loads(raw)
                    except json.JSONDecodeError:
                        continue
                    if item.get("id") == request_id:
                        result = item
                        break
            except OSError:
                pass
        if result:
            break
        time.sleep(0.3)

    if not result:
        fail("等待超时：确认游戏服已加载 wayzer/user/accountMigrate.kts，且目录是同一个 mdtserver")

    old_qq = result.get("oldQq", "")
    new_qq = result.get("newQq", "")
    msg = result.get("message", "")
    if result.get("ok"):
        print(f"[OK] account:{args.account_id} {old_qq} -> {new_qq}")
        print(f"[OK] {msg}")
        print("[NEXT] 让玩家用新 QQ 登录，并执行 /bindemail 绑定新 QQ 邮箱")
        raise SystemExit(0)
    fail(msg or "迁移失败")


if __name__ == "__main__":
    main()
