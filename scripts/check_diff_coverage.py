#!/usr/bin/env python3
import argparse
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def run_git_diff(base_ref: str) -> str:
    cmd = ["git", "diff", "-U0", f"{base_ref}...HEAD", "--", ":(glob)**/src/main/java/**/*.java"]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        fallback = ["git", "diff", "-U0", f"{base_ref}..HEAD", "--", ":(glob)**/src/main/java/**/*.java"]
        proc = subprocess.run(fallback, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.strip() or "git diff failed")
    return proc.stdout


def parse_changed_lines(diff_text: str) -> dict[tuple[str, str], set[int]]:
    changed: dict[tuple[str, str], set[int]] = {}
    current_key: tuple[str, str] | None = None
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            target = line[4:].strip()
            if target == "/dev/null":
                current_key = None
                continue
            if target.startswith("b/"):
                target = target[2:]
            marker = "/src/main/java/"
            if marker in target:
                module, rel = target.split(marker, 1)
                current_key = (module.replace("\\", "/"), rel.replace("\\", "/"))
            elif target.startswith("src/main/java/"):
                current_key = (".", target[len("src/main/java/"):].replace("\\", "/"))
            else:
                current_key = None
            continue
        if current_key is None:
            continue
        match = HUNK_RE.match(line)
        if not match:
            continue
        start = int(match.group(1))
        count = int(match.group(2)) if match.group(2) is not None else 1
        if count <= 0:
            continue
        bucket = changed.setdefault(current_key, set())
        bucket.update(range(start, start + count))
    return changed


def collect_jacoco_lines() -> dict[tuple[str, str], dict[int, bool]]:
    coverage: dict[tuple[str, str], dict[int, bool]] = {}
    for xml_path in sorted(glob.glob("**/target/site/jacoco/jacoco.xml", recursive=True)):
        norm = xml_path.replace("\\", "/")
        module = norm.split("/target/site/jacoco/jacoco.xml", 1)[0]
        if not module:
            module = "."
        tree = ET.parse(xml_path)
        root = tree.getroot()
        for pkg in root.findall("package"):
            pkg_name = pkg.attrib.get("name", "")
            for sf in pkg.findall("sourcefile"):
                sf_name = sf.attrib.get("name", "")
                rel = f"{pkg_name}/{sf_name}" if pkg_name else sf_name
                file_lines = coverage.setdefault((module, rel), {})
                for ln in sf.findall("line"):
                    nr = int(ln.attrib["nr"])
                    ci = int(ln.attrib.get("ci", "0"))
                    mi = int(ln.attrib.get("mi", "0"))
                    if ci > 0 or mi > 0:
                        file_lines[nr] = ci > 0
    return coverage


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--min-coverage", type=float, default=75.0)
    args = parser.parse_args()

    diff_text = run_git_diff(args.base)
    changed = parse_changed_lines(diff_text)
    if not changed:
        print("No changed Java main lines detected.")
        return 0

    jacoco_data = collect_jacoco_lines()
    covered_count = 0
    total_executable = 0
    missed_locations: list[str] = []

    for key, line_numbers in sorted(changed.items()):
        file_cov = jacoco_data.get(key)
        if file_cov is None:
            continue
        for nr in sorted(line_numbers):
            if nr not in file_cov:
                continue
            total_executable += 1
            if file_cov[nr]:
                covered_count += 1
            else:
                missed_locations.append(f"{key[0]}/src/main/java/{key[1]}:{nr}")

    if total_executable == 0:
        print("No executable changed Java lines found in JaCoCo reports.")
        return 0

    pct = (covered_count / total_executable) * 100.0
    print(f"Diff coverage: {covered_count}/{total_executable} ({pct:.2f}%), required >= {args.min_coverage:.2f}%")
    if pct + 1e-9 < args.min_coverage:
        print("Uncovered changed lines:")
        for item in missed_locations[:50]:
            print(f"  {item}")
        return 1
    return 0


if __name__ == "__main__":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    sys.exit(main())
