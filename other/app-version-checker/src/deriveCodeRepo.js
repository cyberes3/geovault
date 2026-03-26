/**
 * Mirrors Android VersionCheckRequest codeRepoPath for each releases repo.
 * geovault-app-release -> owner/geovault; otherwise code repo == releases repo (survey).
 */
export function deriveCodeRepoPath(releasesOwner, releasesRepo) {
  if (releasesRepo === 'geovault-app-release') {
    return `${releasesOwner}/geovault`;
  }
  return `${releasesOwner}/${releasesRepo}`;
}
