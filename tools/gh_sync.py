#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GitHub sync helper for environments where the git://https protocol to
github.com is blocked by a proxy but api.github.com is allowed.

It can:
  * reconstruct <sha>  -> recreate a remote commit (and its full tree + parents)
                          in the LOCAL repo via `git fast-import`, so that the
                          local object store contains the exact same commit SHA
                          GitHub has.  Parents are imported recursively.
  * reset <sha>        -> `git reset --hard <sha>`
  * getref             -> print the current remote main SHA
  * push <msg>         -> build a commit from the working tree via the Git Data
                          API (parent = current remote main), update the ref,
                          then reconcile the local repo onto the new remote SHA.

The reconcile step guarantees that after a push, local `main` == remote `main`
(byte-identical SHAs), which removes any "diverged history" state.
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request
import urllib.error
from datetime import datetime, timezone

REPO = "zwhapp/qingsongreader"
TOKEN = os.environ.get("GH_TOKEN", "")
API = "https://api.github.com"
if not TOKEN:
    print("Error: set GH_TOKEN environment variable.", file=sys.stderr)
    sys.exit(1)

# Fixed commit identity used when creating commits through the API.
AUTHOR_NAME = "zwh"
AUTHOR_EMAIL = "1352732512@qq.com"

_visited = set()


def api_get(path):
    url = API + path
    req = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "qingsongreader-sync",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:2000]
        print("HTTPError on GET %s: %d %s\n%s" % (path, e.code, e.reason, body), file=sys.stderr)
        raise


def api_post(path, payload):
    url = API + path
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "User-Agent": "qingsongreader-sync",
        "X-GitHub-Api-Version": "2022-11-28",
    }, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:2000]
        print("HTTPError on POST %s: %d %s\n%s" % (path, e.code, e.reason, body), file=sys.stderr)
        raise


def api_patch(path, payload):
    url = API + path
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "User-Agent": "qingsongreader-sync",
        "X-GitHub-Api-Version": "2022-11-28",
    }, method="PATCH")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:2000]
        print("HTTPError on PATCH %s: %d %s\n%s" % (path, e.code, e.reason, body), file=sys.stderr)
        raise


def git(*args, input_bytes=None):
    p = subprocess.run(
        ["git"] + list(args),
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if p.returncode != 0:
        raise RuntimeError("git %s failed: %s" % (" ".join(args), p.stderr.decode("utf-8", "replace")))
    return p.stdout


def parse_date(iso):
    """Return (unix_seconds:int, tz:'+0000')."""
    if iso.endswith("Z"):
        iso = iso[:-1] + "+00:00"
    dt = datetime.fromisoformat(iso)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    ts = int(dt.timestamp())
    tz = dt.strftime("%z")
    if tz == "+0000":
        tz = "+0000"
    return ts, tz


def get_blob_bytes(sha):
    info = api_get("/repos/%s/git/blobs/%s" % (REPO, sha))
    content = info.get("content", "")
    encoding = info.get("encoding", "base64")
    if encoding == "base64":
        return base64.b64decode(content)
    return content.encode("utf-8")


def build_commit_stream(sha, mark, out, imported_marks):
    """Emit a fast-import commit for `sha` (and its parents first)."""
    if sha in _visited:
        return imported_marks[sha]
    commit = api_get("/repos/%s/git/commits/%s" % (REPO, sha))
    parents = commit.get("parents", [])

    parent_marks = []
    for p in parents:
        parent_marks.append(build_commit_stream(p["sha"], mark, out, imported_marks))

    tree_sha = commit["tree"]["sha"]
    tree = api_get("/repos/%s/git/trees/%s?recursive=1" % (REPO, tree_sha))
    blobs = [e for e in tree.get("tree", []) if e["type"] == "blob"]

    author = commit["author"]
    committer = commit["committer"]
    a_ts, a_tz = parse_date(author["date"])
    c_ts, c_tz = parse_date(committer["date"])
    message = commit.get("message", "")

    mark[0] += 1
    out.append(("commit", "refs/heads/__remote_main"))
    out.append(("mark", ":%d" % mark[0]))
    out.append(("author", "%s <%s> %d %s" % (author["name"], author["email"], a_ts, a_tz)))
    out.append(("committer", "%s <%s> %d %s" % (committer["name"], committer["email"], c_ts, c_tz)))
    out.append(("data", message))
    for pm in parent_marks:
        out.append(("from", pm))
    out.append(("deleteall", None))
    file_entries = []
    for b in blobs:
        mode = b["mode"]
        path = b["path"]
        raw = get_blob_bytes(b["sha"])
        file_entries.append((mode, path, raw))
    out.append(("files", file_entries))

    _visited.add(sha)
    imported_marks[sha] = ":%d" % mark[0]
    return ":%d" % mark[0]


def serialize_fast_import(items):
    buf = bytearray()
    for kind, value in items:
        if kind == "commit":
            buf += ("commit %s\n" % value).encode("utf-8")
        elif kind == "mark":
            buf += ("mark %s\n" % value).encode("utf-8")
        elif kind == "author":
            buf += ("author %s\n" % value).encode("utf-8")
        elif kind == "committer":
            buf += ("committer %s\n" % value).encode("utf-8")
        elif kind == "data":
            data = value.encode("utf-8")
            buf += ("data %d\n" % len(data)).encode("utf-8")
            buf += data
            buf += b"\n"
        elif kind == "from":
            buf += ("from %s\n" % value).encode("utf-8")
        elif kind == "deleteall":
            buf += b"deleteall\n"
        elif kind == "files":
            for mode, path, raw in value:
                buf += ("M %s inline %s\n" % (mode, path)).encode("utf-8")
                buf += ("data %d\n" % len(raw)).encode("utf-8")
                buf += raw
                buf += b"\n"
    return bytes(buf)


def reconstruct(sha):
    """Recreate remote commit `sha` (and ancestors) in the local repo."""
    _visited.clear()
    items = []
    marks = {}
    counter = [0]
    build_commit_stream(sha, counter, items, marks)
    stream = serialize_fast_import(items)
    git("fast-import", "--date-format=raw", "--quiet", input_bytes=stream)
    local_sha = git("rev-parse", "__remote_main").decode().strip()
    print("RECONSTRUCTED %s -> local %s" % (sha, local_sha))
    return local_sha


def cmd_reconstruct(sha):
    reconstruct(sha)


def cmd_reset(sha):
    git("reset", "--hard", sha)
    print("reset --hard %s" % sha)


def cmd_getref():
    ref = api_get("/repos/%s/git/refs/heads/main" % REPO)
    print(ref["object"]["sha"])


def blob_exists(sha):
    try:
        api_get("/repos/%s/git/blobs/%s" % (REPO, sha))
        return True
    except urllib.error.HTTPError as e:
        # 404 = definitely absent -> caller must upload it.
        if e.code == 404:
            return False
        # Any other HTTP status: assume it exists (don't force a re-upload
        # of a large existing blob, which can fail over a flaky proxy).
        return True
    except Exception:
        # Network truncation (e.g. IncompleteRead) on an existing large blob:
        # treat as present so we skip the re-upload rather than fail the push.
        return True


def cmd_push(message):
    # Normal commit on top of the current remote main. Local main == remote main
    # is already guaranteed, so the parent chain stays consistent on both sides.
    git("add", "-A")
    files = git("ls-files", "--cached").decode("utf-8").splitlines()
    parent_sha = api_get("/repos/%s/git/refs/heads/main" % REPO)["object"]["sha"]
    entries = []
    for f in files:
        if not f.strip():
            continue
        blob_sha = git("hash-object", f).decode().strip()
        raw_bytes = git("cat-file", "blob", blob_sha)
        if not blob_exists(blob_sha):
            api_post("/repos/%s/git/blobs" % REPO, {
                "content": base64.b64encode(raw_bytes).decode("ascii"),
                "encoding": "base64",
            })
        entries.append({"path": f, "mode": "100644", "type": "blob", "sha": blob_sha})
    tree = api_post("/repos/%s/git/trees" % REPO, {"tree": entries})
    now = datetime.now(timezone.utc)
    date_iso = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    commit = api_post("/repos/%s/git/commits" % REPO, {
        "message": message,
        "tree": tree["sha"],
        "parents": [parent_sha],
        "author": {"name": AUTHOR_NAME, "email": AUTHOR_EMAIL, "date": date_iso},
        "committer": {"name": AUTHOR_NAME, "email": AUTHOR_EMAIL, "date": date_iso},
    })
    new_sha = commit["sha"]
    api_patch("/repos/%s/git/refs/heads/main" % REPO, {"sha": new_sha, "force": True})
    print("PUSHED %s (parent %s)" % (new_sha, parent_sha[:10]))
    # Reconcile local repo onto the new remote SHA.
    local_sha = reconstruct(new_sha)
    git("reset", "--hard", local_sha)
    print("LOCAL main now == remote main: %s" % local_sha)


def main():
    if len(sys.argv) < 2:
        print("usage: gh_sync.py <reconstruct|reset|getref|push> [args]")
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "reconstruct":
        cmd_reconstruct(sys.argv[2])
    elif cmd == "reset":
        cmd_reset(sys.argv[2])
    elif cmd == "getref":
        cmd_getref()
    elif cmd == "push":
        msg = sys.argv[2] if len(sys.argv) > 2 else "update"
        cmd_push(msg)
    else:
        print("unknown command: %s" % cmd)
        sys.exit(1)


if __name__ == "__main__":
    main()
