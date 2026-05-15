import { FULL_SHA_REGEX } from './parser.js';
import { compareCommits, isReleaseCommitNewer, resolveCommitSha } from './gitea.js';
import { findLatestMatchForApp } from './resolveLatest.js';
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
 * @returns {Promise<{ status: number, body: object }>}
 */
export async function runCheck(env, payload) {
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

  const match = await findLatestMatchForApp(env, appName, releasesRepo);
  if (!match) {
    return {
      status: 404,
      body: {
        error: 'no_match',
        detail: 'No release asset matched the APK naming regex for this appName',
      },
    };
  }

  const resolved = await resolveCommitSha(
    env,
    match.origin,
    match.codeOwner,
    match.codeRepoName,
    match.releaseCommitRef
  );
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

  const compare = await compareCommits(
    env,
    match.origin,
    match.codeOwner,
    match.codeRepoName,
    localSha,
    resolved
  );
  const newer = isReleaseCommitNewer(compare);

  return {
    status: 200,
    body: {
      isLatest: !newer,
      ...base,
    },
  };
}
