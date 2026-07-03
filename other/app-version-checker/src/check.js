import { FULL_SHA_REGEX } from './parser.js';
import { compareCommits, isReleaseCommitNewer } from './gitea.js';
import { getOrFindLatestMatch } from './resolveLatest.js';
import { getReleaseRepoConfigs } from './repos.js';

export class CheckBadRequest extends Error {
  constructor(message) {
    super(message);
    this.name = 'CheckBadRequest';
  }
}

function assertAllowedReleasesRepo(hint, configs) {
  const h = (hint || '').trim();
  if (!h) return;
  const ok = configs.some((c) => c.releasesRepoPath === h);
  if (!ok) {
    throw new CheckBadRequest('releasesRepo must be one of the configured release repositories');
  }
}

/**
 * @param {Record<string, string | undefined>} env
 * @param {ExecutionContext} ctx
 * @param {object} payload
 * @returns {Promise<{ status: number, body: object }>}
 */
export async function runCheck(env, ctx, payload) {
  const localSha = String(payload.localFullCommitSha || '')
    .trim()
    .toLowerCase();
  const appName = String(payload.appName || '').trim();
  const releasesRepo = String(payload.releasesRepo || '').trim();

  if (!FULL_SHA_REGEX.test(localSha)) {
    throw new CheckBadRequest('localFullCommitSha must be a full 40-character lowercase hex SHA');
  }
  if (!appName) {
    throw new CheckBadRequest('appName is required');
  }

  const configs = getReleaseRepoConfigs(env);
  assertAllowedReleasesRepo(releasesRepo, configs);

  // Cached: identical for every device checking this appName, so this is served from the
  // Workers cache instead of re-scanning Gitea releases on every /check request.
  const latest = await getOrFindLatestMatch(env, ctx, appName, releasesRepo);
  if (!latest.found) {
    return {
      status: 404,
      body: {
        error: 'no_match',
        detail: 'No release asset matched the APK naming regex for this appName',
      },
    };
  }
  const { match, releaseCommitSha: resolved } = latest;
  if (!resolved) {
    return {
      status: 502,
      body: {
        error: 'resolve_failed',
        detail: 'Could not resolve release commit SHA from release tag',
      },
    };
  }

  const base = {
    appName: match.appName,
    versionLabel: match.versionLabel,
    latestApkUrl: match.latestApkUrl,
    apkAssetName: match.apkAssetName,
    apkSizeBytes: match.apkSizeBytes,
    releasePublishedAt: match.releasePublishedAt,
    releaseTitle: match.releaseTitle,
    releasePageUrl: match.releasePageUrl,
    releaseTag: match.releaseTag,
    releaseCommitSha: resolved,
    localCommitSha: localSha,
    releasesRepo: match.releasesRepo,
    codeRepo: match.codeRepo,
  };

  if (resolved === localSha) {
    return {
      status: 200,
      body: {
        isLatest: true,
        ...base,
      },
    };
  }

  const forwardCompare = await compareCommits(
    env,
    match.origin,
    match.codeOwner,
    match.codeRepoName,
    localSha,
    resolved
  );
  let reverseCompare = null;
  if ((forwardCompare.totalCommits ?? 0) > 0) {
    reverseCompare = await compareCommits(
      env,
      match.origin,
      match.codeOwner,
      match.codeRepoName,
      resolved,
      localSha
    );
  }
  const newer = isReleaseCommitNewer(forwardCompare, reverseCompare);

  return {
    status: 200,
    body: {
      isLatest: !newer,
      ...base,
    },
  };
}
