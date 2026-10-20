#!/usr/bin/env python3
"""构建期生成「变更记录」快照 change-log.json。

离线目标机没有 git，运行时无法现查提交历史，所以在打包/编译前把前后端两仓
当前分支的 git log 快照成一个 classpath 资源，随后端 JAR 一起发布。后端
ChangeLogController 只读这个资源，不在运行时调用 git。

用法：
    python3 scripts/gen-change-log.py \
        [--server <后端仓路径>] [--web <前端仓路径>] [--out <输出 json 路径>]

默认：server = 脚本所在仓根目录，web = 同级 ../family-home-web，
out = <server>/fh-boot/src/main/resources/git/change-log.json（该产物已在 .gitignore 中）。
"""
from __future__ import annotations

import argparse
import datetime
import json
import subprocess
import sys
from pathlib import Path

# 单元分隔符 / 记录分隔符：提交主题里几乎不可能出现，用来安全切分 git log 输出。
UNIT_SEP = "\x1f"
RECORD_SEP = "\x1e"
LOG_FORMAT = UNIT_SEP.join(["%H", "%h", "%an", "%aI", "%s"])


def run_git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 在 {repo} 失败：{result.stderr.strip()}")
    return result.stdout


def current_branch(repo: Path) -> str:
    branch = run_git(repo, "rev-parse", "--abbrev-ref", "HEAD").strip()
    # 分离头（打包时可能检出到固定 commit）没有分支名，如实标注而不是伪装成分支。
    return "(detached)" if branch == "HEAD" else branch


def collect_commits(repo: Path) -> list[dict[str, str]]:
    raw = run_git(
        repo,
        "log",
        "--date=iso-strict",
        f"--pretty=format:{LOG_FORMAT}{RECORD_SEP}",
    )
    commits: list[dict[str, str]] = []
    for record in raw.split(RECORD_SEP):
        record = record.strip("\n")
        if not record.strip():
            continue
        parts = record.split(UNIT_SEP)
        if len(parts) != 5:
            continue
        full, short, author, date, subject = parts
        commits.append(
            {
                "hash": full,
                "shortHash": short,
                "author": author,
                "date": date,
                "message": subject,
            }
        )
    return commits


def build_repository(key: str, label: str, repo: Path) -> dict:
    if not (repo / ".git").exists():
        raise RuntimeError(f"{repo} 不是一个 git 仓库")
    commits = collect_commits(repo)
    return {
        "key": key,
        "label": label,
        "branch": current_branch(repo),
        "commitCount": len(commits),
        "commits": commits,
    }


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    server_default = script_dir.parent
    parser = argparse.ArgumentParser(description="生成变更记录快照 change-log.json")
    parser.add_argument("--server", type=Path, default=server_default)
    parser.add_argument("--web", type=Path, default=server_default.parent / "family-home-web")
    parser.add_argument(
        "--out",
        type=Path,
        default=server_default / "fh-boot" / "src" / "main" / "resources" / "git" / "change-log.json",
    )
    args = parser.parse_args()

    payload = {
        "generatedAt": datetime.datetime.now().astimezone().replace(microsecond=0).isoformat(),
        "repositories": [
            build_repository("server", "后端 · family-home-server", args.server.resolve()),
            build_repository("web", "前端 · family-home-web", args.web.resolve()),
        ],
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    total = sum(repo["commitCount"] for repo in payload["repositories"])
    print(f"change-log.json 已生成：{args.out}（两仓共 {total} 条提交）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
