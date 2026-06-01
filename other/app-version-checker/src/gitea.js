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
 * @param {object} forwardCompare Gitea compare local...release (commits on release not in local).
 * @param {object | null} reverseCompare When forward is `diverged`, compare release...local.
 */
export function isReleaseCommitNewer(forwardCompare, reverseCompare = null) {
  const status = forwardCompare?.status;
  if (status === 'ahead') return true;
  if (status === 'identical' || status === 'behind') return false;

  if (status === 'diverged' && reverseCompare) {
    const reverseStatus = reverseCompare.status;
    if (reverseStatus === 'ahead' || reverseStatus === 'identical') {
      return false;
    }
    if (reverseStatus === 'behind') {
      return true;
    }
    if (reverseStatus === 'diverged') {
      const releaseOnly = forwardCompare.totalCommits ?? 0;
      const localOnly = reverseCompare.totalCommits ?? 0;
      if (releaseOnly === 0) return false;
      if (localOnly >= releaseOnly) return false;
      return true;
    }
    return false;
  }

  // Unknown / missing compare data: do not prompt without proof the release is ahead.
  return false;
}

export { TTL_RELEASES, TTL_COMMIT, TTL_COMPARE };
