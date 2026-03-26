import { getReleaseRepoConfigs, getReleasesLimit } from './repos.js';
import { scanReleaseRepo } from './scanReleases.js';
import { resolveCommitSha } from './gitea.js';

const CATALOG_CACHE_KEY = 'https://app-version-checker.internal/catalog-v1';
const CATALOG_MAX_AGE = 300;

/**
 * @param {object} row from scanReleaseRepo
 * @param {Record<string, string | undefined>} env
 */
async function enrichWithResolvedSha(env, row) {
  try {
    const sha = await resolveCommitSha(
      env,
      row.origin,
      row.codeOwner,
      row.codeRepoName,
      row.releaseCommitRef
    );
    if (!sha) {
      return {
        appName: row.appName,
        versionLabel: row.versionLabel,
        assetName: row.assetName,
        latestApkUrl: row.latestApkUrl,
        releasePageUrl: row.releasePageUrl,
        releaseTag: row.releaseTag,
        releasesRepo: row.releasesRepo,
        codeRepo: row.codeRepo,
        releaseCommitSha: null,
        error: 'Could not resolve release commit SHA from release tag',
      };
    }
    return {
      appName: row.appName,
      versionLabel: row.versionLabel,
      assetName: row.assetName,
      latestApkUrl: row.latestApkUrl,
      releasePageUrl: row.releasePageUrl,
      releaseTag: row.releaseTag,
      releasesRepo: row.releasesRepo,
      codeRepo: row.codeRepo,
      releaseCommitSha: sha,
    };
  } catch (e) {
    return {
      appName: row.appName,
      versionLabel: row.versionLabel,
      assetName: row.assetName,
      latestApkUrl: row.latestApkUrl,
      releasePageUrl: row.releasePageUrl,
      releaseTag: row.releaseTag,
      releasesRepo: row.releasesRepo,
      codeRepo: row.codeRepo,
      releaseCommitSha: null,
      error: e.message || String(e),
    };
  }
}

/**
 * @param {Record<string, string | undefined>} env
 * @param {ExecutionContext} ctx
 */
export async function getOrBuildCatalog(env, ctx) {
  const cache = caches.default;
  const cacheRequest = new Request(CATALOG_CACHE_KEY);
  const cached = await cache.match(cacheRequest);
  if (cached) {
    return cached.json();
  }

  const configs = getReleaseRepoConfigs(env);
  const limit = getReleasesLimit(env);
  const rows = [];
  for (const cfg of configs) {
    const scanned = await scanReleaseRepo(env, cfg, limit);
    rows.push(...scanned);
  }

  const enriched = await Promise.all(rows.map((r) => enrichWithResolvedSha(env, r)));
  enriched.sort((a, b) => a.appName.localeCompare(b.appName));

  const payload = {
    scannedAt: new Date().toISOString(),
    repos: configs.map((c) => c.releasesRepoPath),
    apps: enriched,
  };

  const response = new Response(JSON.stringify(payload), {
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': `public, max-age=${CATALOG_MAX_AGE}`,
    },
  });

  ctx.waitUntil(cache.put(cacheRequest, response.clone()));
  return payload;
}
