import { extractCommitRefFromTag, parseApkName } from './parser.js';
import { deriveCodeRepoPath } from './deriveCodeRepo.js';
import { fetchReleasesJson } from './gitea.js';

/**
 * Walk releases in API order; first APK per appName wins (matches ReleaseAssetParser per app).
 * @param {Record<string, string | undefined>} env
 * @param {{ origin: string, owner: string, repo: string, releasesRepoPath: string }} cfg
 * @param {number} limit
 * @returns {Promise<object[]>} rows without resolved full SHA yet
 */
export async function scanReleaseRepo(env, cfg, limit) {
  const releases = await fetchReleasesJson(env, cfg.origin, cfg.owner, cfg.repo, limit);
  if (!Array.isArray(releases)) return [];

  const byApp = new Map();

  for (const release of releases) {
    const tagName = release.tag_name != null ? String(release.tag_name).trim() : '';
    if (!tagName) continue;
    const htmlUrl = release.html_url != null ? String(release.html_url).trim() : '';
    const assets = Array.isArray(release.assets) ? release.assets : [];
    const ref = extractCommitRefFromTag(tagName);
    if (!ref) continue;

    for (const asset of assets) {
      const name = asset.name != null ? String(asset.name).trim() : '';
      const url = asset.browser_download_url != null ? String(asset.browser_download_url).trim() : '';
      if (!name || !url) continue;
      const parsed = parseApkName(name);
      if (!parsed) continue;
      if (byApp.has(parsed.appName)) continue;

      const releasePageUrl =
        htmlUrl ||
        `${cfg.origin}/${cfg.owner}/${cfg.repo}/releases/tag/${encodeURIComponent(tagName)}`;
      const codeRepoPath = deriveCodeRepoPath(cfg.owner, cfg.repo);
      const [codeOwner, codeRepo] = codeRepoPath.split('/');

      byApp.set(parsed.appName, {
        appName: parsed.appName,
        versionLabel: parsed.versionLabel,
        assetName: name,
        latestApkUrl: url,
        releasePageUrl,
        releaseTag: tagName,
        releaseCommitRef: ref,
        releasesRepo: cfg.releasesRepoPath,
        codeRepo: codeRepoPath,
        codeOwner,
        codeRepoName: codeRepo,
        origin: cfg.origin,
      });
    }
  }

  return [...byApp.values()];
}
