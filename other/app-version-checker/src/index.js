import { INDEX_HTML } from './assets.js';
import { getOrBuildCatalog } from './catalog.js';
import { runCheck, CheckBadRequest } from './check.js';
import { GiteaConfigError } from './gitea.js';

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
};

function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      ...CORS_HEADERS,
      ...extraHeaders,
    },
  });
}

function checkPostSecret(request, env) {
  const secret = (env.CHECK_SECRET || '').trim();
  if (!secret) return true;
  const auth = request.headers.get('Authorization');
  if (!auth || !auth.startsWith('Bearer ')) return false;
  return auth.slice(7) === secret;
}

function normalizePath(pathname) {
  const p = pathname.replace(/\/$/, '') || '/';
  return p;
}

/** Collapse duplicate slashes, ensure leading slash, trim trailing slash (except root). */
function canonicalPath(pathname) {
  let p = String(pathname).trim().replace(/\/+/g, '/');
  if (!p || p === '/') return '/';
  if (!p.startsWith('/')) p = `/${p}`;
  return p.replace(/\/$/, '') || '/';
}

function pathEndsWithSegment(pathname, segment) {
  const n = canonicalPath(pathname);
  if (n === segment) return true;
  if (!n.endsWith(segment)) return false;
  const i = n.length - segment.length;
  return i === 0 || n[i - 1] === '/';
}

/**
 * 1) Terminal segments: …/latest, …/check, …/index.html (even without ROUTE_PREFIX).
 * 2) ROUTE_PREFIX strip (canonical match) for mount URL → / and subpaths → /latest etc.
 * 3) If ROUTE_PREFIX is missing in env, treat …/geovault-app-releases (leaf only) as dashboard /.
 */
function effectivePath(pathname, env) {
  const n = canonicalPath(pathname);

  if (pathEndsWithSegment(n, '/latest')) return '/latest';
  if (pathEndsWithSegment(n, '/check')) return '/check';
  if (pathEndsWithSegment(n, '/index.html')) return '/index.html';

  const raw = (env.ROUTE_PREFIX || '').trim();
  if (raw) {
    const prefix = canonicalPath(raw);
    if (n === prefix) return '/';
    if (n.startsWith(`${prefix}/`)) {
      return canonicalPath(n.slice(prefix.length) || '/');
    }
  } else {
    const parts = n.split('/').filter(Boolean);
    const leaf = parts.length ? parts[parts.length - 1] : '';
    if (leaf === 'geovault-app-releases') {
      return '/';
    }
  }

  return n;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = effectivePath(url.pathname, env);
    const method = request.method;

    if (method === 'OPTIONS' && (path === '/check' || path === '/latest')) {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    try {
      if (
        (method === 'GET' || method === 'HEAD') &&
        (path === '/' || path === '/index.html')
      ) {
        return new Response(method === 'HEAD' ? null : INDEX_HTML, {
          status: 200,
          headers: { 'Content-Type': 'text/html; charset=utf-8' },
        });
      }

      if (method === 'GET' && path === '/latest') {
        const catalog = await getOrBuildCatalog(env, ctx);
        return jsonResponse(catalog, 200, { 'Cache-Control': 'public, max-age=60' });
      }

      if (method === 'POST' && path === '/check') {
        if (!checkPostSecret(request, env)) {
          return jsonResponse({ error: 'unauthorized', detail: 'Invalid or missing Bearer token' }, 401);
        }
        let payload;
        try {
          payload = await request.json();
        } catch {
          return jsonResponse({ error: 'bad_request', detail: 'Body must be JSON' }, 400);
        }
        const result = await runCheck(env, payload);
        return jsonResponse(result.body, result.status);
      }

      return new Response('Not Found', { status: 404 });
    } catch (e) {
      if (e instanceof CheckBadRequest) {
        return jsonResponse({ error: 'bad_request', detail: e.message }, 400);
      }
      if (e.name === 'ConfigError') {
        return jsonResponse({ error: 'bad_config', detail: e.message }, 400);
      }
      if (e instanceof GiteaConfigError) {
        return jsonResponse({ error: 'misconfigured', detail: e.message }, 503);
      }
      return jsonResponse(
        { error: 'internal_error', detail: e.message || String(e) },
        502
      );
    }
  },
};
