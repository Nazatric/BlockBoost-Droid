#!/usr/bin/env python3
"""Post a condensed, machine-readable build report to the CI Console issue.

The BlockBoost development sandbox can reach api.github.com but not the artifact
or log hosts, so GitHub Actions uses this script as the feedback channel: every
run appends a comment with (a) the build outcome, (b) compiler/test errors,
(c) release-jar verification, and (d) the dumped Minecraft API surface used to
verify mixin targets against the real 26.1.2 jar.

No credentials other than the job's own GITHUB_TOKEN are used, and nothing is
sent anywhere except this repository's issue tracker.
"""

import json
import os
import re
import subprocess
import sys

MAX_CHUNK = 50000  # comment bodies are capped at 65536 chars by GitHub

ERROR_RE = re.compile(
    r"(^\s*>\s.*FAILED$|^FAILURE:|^BUILD (SUCCESSFUL|FAILED)|error:|\berror!|"
    r"Caused by:|Unresolved|Could not (resolve|find)|MixinApplyError|"
    r"Invalid descriptor|refmap|No such|not found|Exception|FAILED$)",
    re.MULTILINE,
)


def read(path: str, limit: int = 400000) -> str:
    try:
        with open(path, "r", errors="replace") as fh:
            return fh.read(limit)
    except OSError:
        return ""


def tail(text: str, n: int) -> str:
    lines = text.splitlines()
    return "\n".join(lines[-n:])


def extract_errors(log: str) -> str:
    lines = log.splitlines()
    keep = []
    for i, line in enumerate(lines):
        if ERROR_RE.search(line):
            keep.append(line)
    # de-dupe, keep order, cap
    seen = set()
    out = []
    for line in keep:
        s = line.strip()[:400]
        if s not in seen:
            seen.add(s)
            out.append(line[:400])
    return "\n".join(out[-260:])


def java_compile_errors(log: str) -> str:
    """Gradle prints javac diagnostics as '/abs/path/File.java:12: error: message'."""
    pat = re.compile(r"^(/[^\s:]+\.java):\d+:(?:\d+:)? (?:error|warning): .*$", re.MULTILINE)
    hits = []
    seen = set()
    for m in pat.finditer(log):
        line = m.group(0)
        # Strip the build-machine prefix to keep it readable and dedupe repeats.
        line = re.sub(r"^/home/runner/work/blockboost/blockboost/", "", line)
        if line not in seen:
            seen.add(line)
            hits.append(line)
    return "\n".join(hits[:200])


def post(title: str, body: str) -> None:
    repo = os.environ.get("GH_REPO") or ""
    issue = os.environ.get("CONSOLE_ISSUE") or "1"

    # Split into comments that stay comfortably below GitHub's body limit.
    chunks: list[str] = []
    remaining = body
    while remaining:
        cut = min(len(remaining), MAX_CHUNK)
        if cut < len(remaining):
            # Prefer breaking on a newline so fenced blocks stay readable.
            nl = remaining.rfind("\n", 0, cut)
            if nl > MAX_CHUNK // 2:
                cut = nl
        chunks.append(remaining[:cut])
        remaining = remaining[cut:]
        if len(chunks) >= 4:  # hard cap: never spam the issue
            chunks[-1] += "\n\n…(report truncated)"
            break

    for i, chunk in enumerate(chunks, start=1):
        header = f"### {title}" if len(chunks) == 1 else f"### {title} — part {i}/{len(chunks)}"
        payload = {"body": header + "\n\n" + chunk}
        proc = subprocess.run(
            ["gh", "api", f"repos/{repo}/issues/{issue}/comments", "--input", "-"],
            input=json.dumps(payload),
            text=True,
            capture_output=True,
        )
        if proc.returncode != 0:
            print("failed to post comment:", proc.stderr, file=sys.stderr)


def main() -> int:
    title = os.environ.get("TITLE") or "Build"
    job = os.environ.get("JOB", "job")
    sha = os.environ.get("SHA", "")[:9]
    event = os.environ.get("EVENT", "")
    run_url = os.environ.get("RUN_URL", "")

    gradle_log = read("/tmp/gradle-build.log")
    if not gradle_log:
        gradle_log = read("/tmp/gametest.log")
    custom = read(os.environ.get("REPORT_FILE", "/tmp/__none__"))
    jar_verify = read("/tmp/jar-verify.txt")
    discovery = read("/tmp/discovery.txt")
    api_dump = read("build/recon/api.txt", limit=120000)

    status = "UNKNOWN"
    if "BUILD SUCCESSFUL" in gradle_log:
        status = "SUCCESS"
    elif "BUILD FAILED" in gradle_log or "FAILURE:" in gradle_log:
        status = "FAILURE"

    lines = [
        f"## {title}: **{status}**",
        f"`job={job}` `event={event}` `sha={sha}` [run log]({run_url})",
        "",
    ]
    lines.append(f"### Gradle errors / test failures ({len(ERROR_RE.findall(gradle_log))} matched lines)")
    errs = java_compile_errors(gradle_log) or extract_errors(gradle_log) or "(no error lines captured)"
    lines.append("```text")
    lines.append(errs[:20000] or tail(gradle_log, 200))
    lines.append("```")

    if status != "SUCCESS" and gradle_log:
        lines.append("")
        lines.append("<details><summary>gradle log tail (120 lines)</summary>")
        lines.append("```text")
        lines.append(tail(gradle_log, 120))
        lines.append("```")
        lines.append("</details>")

    if jar_verify:
        lines.append("")
        lines.append("### Jar verification")
        lines.append("```text")
        lines.append(jar_verify[:14000])
        lines.append("```")

    if custom:
        lines.append("")
        lines.append("### Custom report")
        lines.append("```text")
        lines.append(custom[:14000])
        lines.append("```")

    if discovery:
        lines.append("")
        lines.append("<details><summary>dependency discovery</summary>")
        lines.append("```text")
        lines.append(discovery[:6000])
        lines.append("```")
        lines.append("</details>")

    if api_dump:
        lines.append("")
        budget = 90000
        if len(api_dump) <= budget:
            body = api_dump
        else:
            # Too large for the issue body: keep every class header plus the
            # signature lines that mention the APIs BlockBoost actually touches.
            kws = [k.strip().lower() for k in read("tools/recon-keywords.txt").splitlines()
                   if k.strip() and not k.strip().startswith("#")]
            kept = []
            for raw in api_dump.splitlines():
                if raw.startswith("=====") or raw.startswith("(total:") or any(k in raw.lower() for k in kws):
                    kept.append(raw)
            body = "(keyword-filtered from %d chars; full dump: build/recon/api.txt artifact)\n\n" % len(api_dump) + "\n".join(kept)
            body = body[:budget]
        lines.append("<details><summary>Minecraft 26.1.2 API dump (javap, verified targets)</summary>")
        lines.append("```text")
        lines.append(body)
        lines.append("```")
        lines.append("</details>")

    post(f"CI {job} @ {sha}", "\n".join(lines))
    print(f"reported {status} to issue {os.environ.get('CONSOLE_ISSUE', '1')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
