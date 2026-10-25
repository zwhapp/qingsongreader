import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Mirror a GitHub release (metadata + assets) to the Gitee repo via Gitee API v5.
// Runs inside .github/workflows/gitee-sync.yml with env:
//   GITEE_REPO, GITEE_TOKEN, GH_TOKEN, TAG
// Node >= 18 (global fetch). No dependencies.

const REPO = process.env.GITEE_REPO || "zwhapp/qingsongreader";
const TAG = process.env.TAG || process.env.GITHUB_REF_NAME;
const GITEE = "https://gitee.com/api/v5";
const GH = "https://api.github.com";

if (!process.env.GITEE_TOKEN) {
  console.error("ERROR: GITEE_TOKEN not set");
  process.exit(1);
}
if (!TAG) {
  console.error("ERROR: TAG not set");
  process.exit(1);
}

const ghHeaders = {
  Authorization: `Bearer ${process.env.GH_TOKEN}`,
  Accept: "application/vnd.github+json",
  "X-GitHub-Api-Version": "2022-11-28",
  "User-Agent": "qingsongreader-gitee-sync",
};

// Gitee API v5 authenticates via the access_token query parameter (works for
// GET/POST/PATCH and multipart uploads alike). Accepts either a path
// ("/repos/x/y") or a full URL as input.
function giteeUrl(url) {
  const path = url.startsWith(GITEE) ? url.slice(GITEE.length) : url;
  const sep = path.includes("?") ? "&" : "?";
  return `${GITEE}${path}${sep}access_token=${encodeURIComponent(process.env.GITEE_TOKEN)}`;
}

async function ghJson(url) {
  const r = await fetch(url, { headers: ghHeaders });
  if (!r.ok) throw new Error(`GitHub ${url} -> ${r.status}`);
  return r.json();
}

async function giteeJson(url, opts = {}) {
  const r = await fetch(giteeUrl(url), {
    ...opts,
    headers: { "User-Agent": "qingsongreader-gitee-sync", ...(opts.headers || {}) },
  });
  const text = await r.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    /* non-JSON error page */
  }
  return { ok: r.ok, status: r.status, body };
}

async function main() {
  // 1. source release from GitHub
  const gh = await ghJson(`${GH}/repos/${REPO}/releases/tags/${TAG}`);
  console.log(`github release: ${gh.tag_name} (${gh.name}), assets: ${gh.assets.length}`);

  // 2. find or create the Gitee release
  let rel = (await giteeJson(`${GITEE}/repos/${REPO}/releases/tags/${TAG}`)).body;
  if (!rel || !rel.id) {
    const created = await giteeJson(`${GITEE}/repos/${REPO}/releases`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        tag_name: gh.tag_name,
        name: gh.name || gh.tag_name,
        body: gh.body || "",
        target_commitish: "main",
        prerelease: false,
      }),
    });
    if (!created.ok) {
      throw new Error(`create gitee release -> ${created.status} ${JSON.stringify(created.body).slice(0, 300)}`);
    }
    rel = created.body;
    console.log(`gitee release created: id=${rel.id}`);
  } else {
    // keep metadata fresh (body may have been edited after the first sync)
    const patched = await giteeJson(`${GITEE}/repos/${REPO}/releases/${rel.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        tag_name: gh.tag_name,
        name: gh.name || gh.tag_name,
        body: gh.body || "",
      }),
    });
    if (!patched.ok) {
      throw new Error(`patch gitee release -> ${patched.status} ${JSON.stringify(patched.body).slice(0, 200)}`);
    }
    console.log(`gitee release exists: id=${rel.id}`);
  }

  // 3. mirror every asset
  const existing = new Map((rel.assets || []).map((a) => [a.name, a]));
  for (const asset of gh.assets) {
    if (existing.has(asset.name)) {
      console.log(`skip (already present): ${asset.name}`);
      continue;
    }
    // asset.url is the API URL; the binary body requires octet-stream Accept
    const dl = await fetch(asset.url, {
      headers: { ...ghHeaders, Accept: "application/octet-stream" },
    });
    if (!dl.ok) throw new Error(`download ${asset.name} -> ${dl.status}`);
    const buf = Buffer.from(await dl.arrayBuffer());
    if (buf.length < 1024) throw new Error(`download ${asset.name} -> suspicious size ${buf.length}`);
    console.log(`downloaded ${asset.name} (${buf.length} bytes)`);

    // Upload via curl: node's undici aborts slow multipart uploads at its
    // default 300s headersTimeout (Gitee throttles large uploads), while curl
    // simply waits.
    const tmp = path.join(os.tmpdir(), asset.name);
    fs.writeFileSync(tmp, buf);
    const uploadUrl = giteeUrl(`${GITEE}/repos/${REPO}/releases/${rel.id}/attach_files`);
    let ok = false;
    for (let attempt = 1; attempt <= 3 && !ok; attempt++) {
      try {
        const out = execFileSync(
          "curl",
          [
            "-sS", "--fail-with-body", "-X", "POST",
            "-H", "Expect:", // avoid curl's default "Expect: 100-continue" wait
            "-F", `file=@${tmp};filename=${asset.name};type=application/vnd.android.package-archive`,
            uploadUrl,
          ],
          { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
        );
        console.log(`uploaded to gitee: ${asset.name} (attempt ${attempt}) ${out.slice(0, 120)}`);
        ok = true;
      } catch (e) {
        console.log(`upload attempt ${attempt} failed: ${(e.stderr || e.message || "").toString().slice(0, 200)}`);
        if (attempt < 3) await new Promise((r) => setTimeout(r, 10000));
      }
    }
    fs.unlinkSync(tmp);
    if (!ok) throw new Error(`upload ${asset.name} failed after 3 attempts`);
  }

  // 4. verify
  const check = (await giteeJson(`${GITEE}/repos/${REPO}/releases/tags/${TAG}`)).body;
  const names = (check.assets || []).map((a) => a.name).sort();
  const want = gh.assets.map((a) => a.name).sort();
  const missing = want.filter((n) => !names.includes(n));
  if (missing.length) {
    throw new Error(`Gitee release missing assets: ${missing.join(", ")}`);
  }
  console.log(`VERIFIED: gitee ${TAG} has all ${want.length} assets`);
  console.log("DONE");
}

main().catch((e) => {
  console.error("FAILED:", e.message);
  process.exit(1);
});
