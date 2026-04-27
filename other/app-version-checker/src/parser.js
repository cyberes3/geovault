export const FULL_SHA_REGEX = /^[0-9a-f]{40}$/;
const TAG_COMMIT_REGEX = /([0-9a-fA-F]{7,40})$/;
// Asset filenames are <Title-Hyphenated>-<YYYY-MM-DD>-<10-hex>.apk
// e.g. "GeoVault-NGS-Navigator-2026-04-26-0dea2b95c2.apk".
export const APK_NAME_REGEX = /^([A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)-(\d{4}-\d{2}-\d{2})-([0-9a-fA-F]{10})\.apk$/;

export function extractCommitRefFromTag(tagName) {
  const m = TAG_COMMIT_REGEX.exec(String(tagName).trim());
  return m ? m[1].toLowerCase() : null;
}

export function parseApkName(assetName) {
  const m = APK_NAME_REGEX.exec(String(assetName).trim());
  if (!m || m.length < 4) return null;
  const appName = m[1].replace(/-/g, ' ').trim();
  const versionLabel = `${m[2]} ${m[3]}`.trim();
  if (!appName || !versionLabel) return null;
  return { appName, versionLabel };
}
