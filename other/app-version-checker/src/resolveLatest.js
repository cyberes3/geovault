import { extractCommitRefFromTag, parseApkName } from './parser.js';
import { deriveCodeRepoPath } from './deriveCodeRepo.js';
import { fetchReleasesJson } from './gitea.js';
import { getReleaseRepoConfigs, getReleasesLimit } from './repos.js';

/**
 * Same semantics as ReleaseAssetParser.findFirstMatchingReleaseAsset + repo scope.
 * @param {Record<string, string | undefined>} env
 * @param {string} appName
 * @param {string} [releasesRepoHint] owner/repo must match a configured repo
 */
export async function findLatestMatchForApp(env, appName, releasesRepoHint) {
  const target = String(appName).trim();
  if (!target) return null;

  const configs = getReleaseRepoConfigs(env);
  const limit = getReleasesLimit(env);
  const hint = (releasesRepoHint || '').trim();

  for (const cfg of configs) {
    if (hint && cfg.releasesRepoPath !== hint) continue;

    const releases = await fetchReleasesJson(env, cfg.origin, cfg.owner, cfg.repo, limit);
    if (!Array.isArray(releases)) continue;

    for (const release of releases) {
      const tagName = release.tag_name != null ? String(release.tag_name).trim() : '';
      if (!tagName) continue;
      const htmlUrl = release.html_url != null ? String(release.html_url).trim() : '';
      const releasePublishedAt =
        release.published_at != null ? String(release.published_at).trim() : '';
      const releaseTitle = release.name != null ? String(release.name).trim() : '';
      const assets = Array.isArray(release.assets) ? release.assets : [];
      const ref = extractCommitRefFromTag(tagName);
      if (!ref) continue;

      for (const asset of assets) {
        const name = asset.name != null ? String(asset.name).trim() : '';
        const url = asset.browser_download_url != null ? String(asset.browser_download_url).trim() : '';
        if (!name || !url) continue;
        const parsed = parseApkName(name);
        if (!parsed || parsed.appName !== target) continue;

        const rawSize = asset.size != null ? Number(asset.size) : NaN;
        const apkSizeBytes = Number.isFinite(rawSize) && rawSize >= 0 ? Math.trunc(rawSize) : null;

        const codeRepoPath = deriveCodeRepoPath(cfg.owner, cfg.repo);
        const [codeOwner, codeRepoName] = codeRepoPath.split('/');
        const releasePageUrl =
          htmlUrl ||
          `${cfg.origin}/${cfg.owner}/${cfg.repo}/releases/tag/${encodeURIComponent(tagName)}`;

        return {
          appName: parsed.appName,
          versionLabel: parsed.versionLabel,
          assetName: name,
          apkAssetName: name,
          apkSizeBytes,
          releasePublishedAt,
          releaseTitle,
          latestApkUrl: url,
          releasePageUrl,
          releaseTag: tagName,
          releaseCommitRef: ref,
          releasesRepo: cfg.releasesRepoPath,
          codeRepo: codeRepoPath,
          codeOwner,
          codeRepoName,
          origin: cfg.origin,
        };
      }
    }
  }

  return null;
}
