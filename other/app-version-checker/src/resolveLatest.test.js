import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { getOrFindLatestMatch } from './resolveLatest.js';

function makeEnv(overrides = {}) {
  return {
    GITEA_USER_AGENT: 'test-agent',
    RELEASE_REPO_URLS: JSON.stringify(['https://git.example.com/owner/repo']),
    RELEASES_LIMIT: '20',
    ...overrides,
  };
}

/** Minimal in-memory stand-in for the Workers Cache API (`caches.default`). */
function makeFakeCache() {
  const store = new Map();
  return {
    async match(request) {
      const cached = store.get(request.url);
      return cached ? cached.clone() : undefined;
    },
    async put(request, response) {
      store.set(request.url, response.clone());
    },
  };
}

/** Minimal stand-in for ExecutionContext: awaits background work immediately for tests. */
function makeCtx() {
  return { waitUntil: (promise) => promise };
}

const SHA_A = 'a'.repeat(40);

function releasesResponse(releases) {
  return new Response(JSON.stringify(releases), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function releaseFixture({ tag = 'v1-abc1234', assetName = 'My-App-2026-01-01-abc1234567.apk' } = {}) {
  return {
    tag_name: tag,
    html_url: `https://git.example.com/owner/repo/releases/tag/${tag}`,
    published_at: '2026-01-01T00:00:00Z',
    name: 'Release 1',
    assets: [
      {
        name: assetName,
        browser_download_url: 'https://git.example.com/owner/repo/releases/download/v1/app.apk',
        size: 1234,
      },
    ],
  };
}

describe('getOrFindLatestMatch', () => {
  let originalFetch;
  let originalCaches;
  let fakeCache;
  let fetchCalls;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    originalCaches = globalThis.caches;
    fakeCache = makeFakeCache();
    globalThis.caches = { default: fakeCache };
    fetchCalls = [];
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    globalThis.caches = originalCaches;
  });

  function stubFetch(handler) {
    globalThis.fetch = async (req) => {
      fetchCalls.push(req.url);
      return handler(req.url);
    };
  }

  it('resolves the latest match + commit SHA on a cache miss', async () => {
    stubFetch((url) => {
      if (url.includes('/releases')) return releasesResponse([releaseFixture()]);
      if (url.includes('/git/commits/')) {
        return new Response(JSON.stringify({ sha: SHA_A }), { status: 200 });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const result = await getOrFindLatestMatch(makeEnv(), makeCtx(), 'My App', '');

    assert.equal(result.found, true);
    assert.equal(result.releaseCommitSha, SHA_A);
    assert.equal(result.match.appName, 'My App');
    assert.equal(result.match.releaseTag, 'v1-abc1234');
    assert.equal(fetchCalls.length, 2, 'one releases fetch + one commit resolve fetch');
  });

  it('serves a second call for the same appName entirely from cache (no new Gitea fetches)', async () => {
    stubFetch((url) => {
      if (url.includes('/releases')) return releasesResponse([releaseFixture()]);
      if (url.includes('/git/commits/')) {
        return new Response(JSON.stringify({ sha: SHA_A }), { status: 200 });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const env = makeEnv();
    const ctx = makeCtx();
    const first = await getOrFindLatestMatch(env, ctx, 'My App', '');
    const callsAfterFirst = fetchCalls.length;

    const second = await getOrFindLatestMatch(env, ctx, 'My App', '');

    assert.deepEqual(second, first);
    assert.equal(fetchCalls.length, callsAfterFirst, 'no additional fetches on cache hit');
  });

  it('caches a negative result (no matching asset) so repeated lookups skip re-scanning releases', async () => {
    stubFetch((url) => {
      if (url.includes('/releases')) return releasesResponse([releaseFixture()]);
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const env = makeEnv();
    const ctx = makeCtx();
    const first = await getOrFindLatestMatch(env, ctx, 'Totally Different App', '');
    assert.deepEqual(first, { found: false });
    const callsAfterFirst = fetchCalls.length;

    const second = await getOrFindLatestMatch(env, ctx, 'Totally Different App', '');
    assert.deepEqual(second, { found: false });
    assert.equal(fetchCalls.length, callsAfterFirst, 'negative result served from cache too');
  });

  it('uses independent cache entries per appName', async () => {
    stubFetch((url) => {
      if (url.includes('/releases')) {
        return releasesResponse([
          releaseFixture({ tag: 'v1-abc1234', assetName: 'My-App-2026-01-01-abc1234567.apk' }),
          releaseFixture({ tag: 'v2-def4567', assetName: 'Other-App-2026-01-02-def4567890.apk' }),
        ]);
      }
      if (url.includes('/git/commits/')) {
        return new Response(JSON.stringify({ sha: SHA_A }), { status: 200 });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const env = makeEnv();
    const ctx = makeCtx();
    const myApp = await getOrFindLatestMatch(env, ctx, 'My App', '');
    const otherApp = await getOrFindLatestMatch(env, ctx, 'Other App', '');

    assert.equal(myApp.match.appName, 'My App');
    assert.equal(otherApp.match.appName, 'Other App');
  });

  it('does not resolve a commit SHA (and returns releaseCommitSha: null) when resolution fails', async () => {
    stubFetch((url) => {
      if (url.includes('/releases')) return releasesResponse([releaseFixture()]);
      if (url.includes('/git/commits/')) {
        // Gitea returns something without a valid sha field.
        return new Response(JSON.stringify({}), { status: 200 });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const result = await getOrFindLatestMatch(makeEnv(), makeCtx(), 'My App', '');
    assert.equal(result.found, true);
    assert.equal(result.releaseCommitSha, null);
  });
});
