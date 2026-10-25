// Mirror a GitHub release (metadata + assets) to the Gitee repo via Gitee API v5.
// Runs inside .github/workflows/gitee-sync.yml with env:
//   GITEE_REPO, GITEE_TOKEN, GH_TOKEN, TAG
// Node >= 18 (global fetch / FormData / Blob). No dependencies.

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

async function ghJson(url) {
  const r = await fetch(url, { headers: ghHeaders });
  if (!r.ok) throw new Error(`GitHub ${url} -> ${r.status}`);
  return r.json();
}

async function giteeJson(url, opts = {}) {
  const r = await fetch(url, {
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
      body: JSON.stringify({ name: gh.name || gh.tag_name, body: gh.body || "" }),
    });
    if (!patched.ok) {
      console.log(`WARN: patch gitee release -> ${patched.status} (continuing)`);
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
    const dl = await fetch(asset.url, { headers: ghHeaders }); // API url -> binary download
    if (!dl.ok) throw new Error(`download ${asset.name} -> ${dl.status}`);
    const buf = Buffer.from(await dl.arrayBuffer());
    console.log(`downloaded ${asset.name} (${buf.length} bytes)`);

    const fd = new FormData();
    fd.append("file", new Blob([buf]), asset.name);
    const up = await giteeJson(`${GITEE}/repos/${REPO}/releases/${rel.id}/attach_files`, {
      method: "POST",
      body: fd,
    });
    if (!up.ok) {
      throw new Error(`upload ${asset.name} -> ${up.status} ${JSON.stringify(up.body).slice(0, 300)}`);
    }
    console.log(`uploaded to gitee: ${asset.name}`);
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
