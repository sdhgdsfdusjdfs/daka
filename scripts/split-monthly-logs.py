"""Split existing repository Markdown logs by date, preserving monthly archives."""
from pathlib import Path
import argparse
import re


def rows(text):
    return [line for line in text.splitlines() if re.match(r"^\| \d{4}-\d{2}-\d{2} ", line)]


def key(line):
    return tuple(part.strip() for part in line.split("|")[1:4])


def split_logs(devices_root):
    for log_dir in sorted(devices_root.glob("*/logs")):
        grouped = {}
        headers = {}
        for monthly in sorted(log_dir.glob("????-??.md")):
            text = monthly.read_text(encoding="utf-8")
            table_header = next((line for line in text.splitlines() if line.startswith("| 计划时间 |")), None)
            separator = next((line for line in text.splitlines() if line.startswith("|---")), None)
            if not table_header or not separator:
                continue
            for line in rows(text):
                date = line.split("|")[1].strip()[:10]
                grouped.setdefault(date, {})[key(line)] = line
                headers[date] = (table_header, separator)
        for date, source_rows in grouped.items():
            daily = log_dir / f"{date}.md"
            if daily.exists():
                # Keep newer native daily data and any expanded execution details.
                existing = daily.read_text(encoding="utf-8")
                existing_keys = {key(line) for line in rows(existing)}
                additions = [line for k, line in source_rows.items() if k not in existing_keys]
                if additions:
                    daily.write_text(existing.rstrip() + "\n\n## 月度存档补充记录\n\n" + "\n".join(headers[date]) + "\n" + "\n".join(sorted(additions)) + "\n", encoding="utf-8")
                continue
            heading = f"# {log_dir.parent.name} · {date} 执行记录\n\n时间均为北京时间。由原月度文件拆分，保留原始记录内容。\n\n"
            daily.write_text(heading + "\n".join(headers[date]) + "\n" + "\n".join(sorted(source_rows.values())) + "\n", encoding="utf-8")
        dates = sorted((p.stem for p in log_dir.glob("????-??-??.md")), reverse=True)
        index = "# 按日期查看执行日志\n\n每天一个文件，日期按北京时间计算。\n\n"
        index += "\n".join(f"- [{date}]({date}.md)" for date in dates) if dates else "等待首次执行记录。"
        index += "\n\n旧月度文件保留为历史存档。手机统一升级后会自动按日期更新。\n"
        (log_dir / "README.md").write_text(index, encoding="utf-8")
        print(log_dir.parent.name, "daily files:", ", ".join(dates) or "none")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("devices_root", type=Path)
    split_logs(parser.parse_args().devices_root)
