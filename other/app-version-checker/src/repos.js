/**
 * @param {string} urlString
 * @returns {{ origin: string, owner: string, repo: string, releasesRepoPath: string }}
 */
export function parseReleaseRepoUrl(urlString) {
  const u = new URL(urlString.trim());
  if (u.protocol !== 'https:') {
    const err = new Error(`Release repo URL must be https: ${urlString}`);
    err.name = 'ConfigError';
    throw err;
  }
  const segments = u.pathname.replace(/^\/+|\/+$/g, '').split('/').filter(Boolean);
  if (segments.length < 2) {
    const err = new Error(`Release repo URL must be https://host/owner/repo: ${urlString}`);
    err.name = 'ConfigError';
    throw err;
  }
  const owner = segments[0];
  const repo = segments[1];
  if (!owner || !repo) {
    const err = new Error(`Invalid owner/repo in URL: ${urlString}`);
    err.name = 'ConfigError';
    throw err;
  }
  return {
    origin: u.origin,
    owner,
    repo,
    releasesRepoPath: `${owner}/${repo}`,
  };
}

/**
 * @param {Record<string, string | undefined>} env
 * @returns {ReturnType<typeof parseReleaseRepoUrl>[]}
 */
export function getReleaseRepoConfigs(env) {
  const raw = (env.RELEASE_REPO_URLS || '').trim();
  if (!raw) {
    const err = new Error(
      'RELEASE_REPO_URLS is not set; define it in wrangler.toml [vars] as a JSON array of https release repo URLs'
    );
    err.name = 'ConfigError';
    throw err;
  }
  let urls;
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed) || !parsed.every((x) => typeof x === 'string') || parsed.length === 0) {
      const err = new Error('RELEASE_REPO_URLS must be a non-empty JSON array of strings');
      err.name = 'ConfigError';
      throw err;
    }
    urls = parsed;
  } catch (e) {
    if (e.name === 'ConfigError') throw e;
    const err = new Error(`RELEASE_REPO_URLS: invalid JSON — ${e.message}`);
    err.name = 'ConfigError';
    throw err;
  }
  return urls.map((u) => parseReleaseRepoUrl(u));
}

export function getReleasesLimit(env) {
  const n = parseInt(String(env.RELEASES_LIMIT || '20'), 10);
  if (!Number.isFinite(n) || n < 1) return 20;
  return Math.min(n, 100);
}
