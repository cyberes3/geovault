const TTL_RELEASES = 600;
const TTL_COMMIT = 86400;
const TTL_COMPARE = 86400;

function requireUserAgent(env) {
  const ua = (env.GITEA_USER_AGENT || '').trim();
  if (!ua) {
    throw new GiteaConfigError('GITEA_USER_AGENT secret is not set');
  }
  return ua;
}

export class GiteaConfigError extends Error {
  constructor(message) {
    super(message);
    this.name = 'GiteaConfigError';
  }
}

/**
 * @param {Record<string, string | undefined>} env
 * @param {string} origin e.g. https://git.evulid.cc
 * @param {string} apiPath e.g. /api/v1/repos/o/r/releases?limit=20
 * @param {number} cacheTtlSeconds
 */
export async function giteaGet(env, origin, apiPath, cacheTtlSeconds) {
  const ua = requireUserAgent(env);
  const url = `${origin}${apiPath.startsWith('/') ? '' : '/'}${apiPath}`;
  const headers = {
    Accept: 'application/json',
    'User-Agent': ua,
  };
  const token = (env.GITEA_TOKEN || '').trim();
  if (token) {
    headers.Authorization = `token ${token}`;
  }
  const req = new Request(url, {
    method: 'GET',
    headers,
  });
  const res = await fetch(req, {
    cf: {
      cacheEverything: true,
      cacheTtl: cacheTtlSeconds,
    },
  });
  if (!res.ok) {
    const snippet = await res.text().then((t) => t.slice(0, 200)).catch(() => '');
    throw new Error(`Gitea HTTP ${res.status} ${url} ${snippet.replace(/\s+/g, ' ')}`);
  }
  return res.json();
}

export async function fetchReleasesJson(env, origin, owner, repo, limit) {
  const path = `/api/v1/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/releases?limit=${limit}`;
  return giteaGet(env, origin, path, TTL_RELEASES);
}

export async function resolveCommitSha(env, origin, codeOwner, codeRepo, commitRef) {
  const normalized = String(commitRef).trim().toLowerCase();
  if (!normalized) return null;
  if (/^[0-9a-f]{40}$/.test(normalized)) return normalized;
  const path = `/api/v1/repos/${encodeURIComponent(codeOwner)}/${encodeURIComponent(codeRepo)}/git/commits/${encodeURIComponent(normalized)}`;
  const json = await giteaGet(env, origin, path, TTL_COMMIT);
  const sha = String(json.sha || '')
    .trim()
    .toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(sha)) return null;
  return sha;
}

export async function compareCommits(env, origin, codeOwner, codeRepo, baseCommit, headCommit) {
  const base = String(baseCommit).trim().toLowerCase();
  const head = String(headCommit).trim().toLowerCase();
  const path = `/api/v1/repos/${encodeURIComponent(codeOwner)}/${encodeURIComponent(codeRepo)}/compare/${base}...${head}`;
  const json = await giteaGet(env, origin, path, TTL_COMPARE);
  const status = json.status != null ? String(json.status).trim().toLowerCase() || null : null;
  const totalCommits = json.total_commits != null ? Number(json.total_commits) : null;
  return { status, totalCommits: Number.isFinite(totalCommits) ? totalCommits : null };
}

/**
 * Whether the release commit is strictly newer than the local build.
 *
 * Uses `total_commits` from the Gitea compare API (the only numeric field it returns;
 * there is no `status` field in the Gitea response).
 *
 * @param {object} forwardCompare  Result of compare/local...release — commits on release path not in local.
 * @param {object | null} reverseCompare  Result of compare/release...local — commits unique to local (dev-ahead indicator).
 */
export function isReleaseCommitNewer(forwardCompare, reverseCompare = null) {
  const forwardCount = forwardCompare?.totalCommits ?? 0;

  // No commits on the release path that aren't already in local → not newer.
  if (forwardCount === 0) return false;

  if (reverseCompare !== null) {
    const reverseCount = reverseCompare?.totalCommits ?? 0;
    // Local has at least as many unique commits as the release — treat as up to date.
    // This handles dev builds that are ahead of or equally diverged from the published tag.
    if (reverseCount >= forwardCount) return false;
  }

  return true;
}

export { TTL_RELEASES, TTL_COMMIT, TTL_COMPARE };
