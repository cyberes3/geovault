import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveExtensionIcon, createHeroiconResolver } from './resolveExtensionIcon.ts';

const FakeIconComponent = { name: 'FakeIcon' };
const stubResolveHeroicon = (name) => name === 'MapIcon'
  ? Promise.resolve(FakeIconComponent)
  : Promise.reject(new Error(`Unknown heroicon: "${name}"`));

test('returns null when the extension has no icon', async () => {
  assert.equal(await resolveExtensionIcon(null, 'my-ext', stubResolveHeroicon), null);
  assert.equal(await resolveExtensionIcon(undefined, 'my-ext', stubResolveHeroicon), null);
  assert.equal(await resolveExtensionIcon('', 'my-ext', stubResolveHeroicon), null);
});

test('resolves a known heroicon name to a component', async () => {
  const icon = await resolveExtensionIcon('MapIcon', 'my-ext', stubResolveHeroicon);
  assert.equal(icon, FakeIconComponent);
});

test('rejects for an unknown heroicon-like name instead of silently resolving to null', async () => {
  await assert.rejects(
    () => resolveExtensionIcon('NotARealIconName', 'my-ext', stubResolveHeroicon),
    /Unknown heroicon/
  );
});

test('resolves an inline <svg> string to a component', async () => {
  const icon = await resolveExtensionIcon('<svg viewBox="0 0 24 24"><path d="M0 0"/></svg>', 'my-ext', stubResolveHeroicon);
  assert.notEqual(icon, null);
  assert.equal(typeof icon, 'object');
});

test('a bare "icon.svg" name skips heroicon resolution entirely (no slash, but has the .svg extension)', async () => {
  // Regression check for caltopo's manifest `icon = "icon.svg"`: this must never be treated as an
  // unrecognized heroicon name (which would now reject) - it has to fall through to the SVG-file
  // fetch path below instead. Using a resolver that always rejects proves heroicon resolution was
  // never attempted; the httpClient call inside will fail in this test environment (no server), so
  // this only asserts it *tries* the file path (returns null after a failed fetch) rather than
  // throwing the "Unknown heroicon" error.
  const alwaysRejects = () => Promise.reject(new Error('should not be called for icon.svg'));
  const icon = await resolveExtensionIcon('icon.svg', 'my-ext', alwaysRejects);
  assert.equal(icon, null);
});

test('createHeroiconResolver resolves a known icon name to its component', async () => {
  const outline = { '/node_modules/@heroicons/vue/24/outline/MapIcon.js': () => Promise.resolve({ name: 'MapIcon' }) };
  const resolve = createHeroiconResolver(outline);

  const icon = await resolve('MapIcon');
  assert.equal(icon.name, 'MapIcon');
});

test('createHeroiconResolver rejects for an unknown name instead of returning null', async () => {
  const resolve = createHeroiconResolver({});
  await assert.rejects(() => resolve('NotARealIconName'), /Unknown heroicon/);
});

test('createHeroiconResolver caches repeated lookups for the same name (loader called only once)', async () => {
  let callCount = 0;
  const outline = {
    '/node_modules/@heroicons/vue/24/outline/MapIcon.js': () => {
      callCount++;
      return Promise.resolve({ name: 'OutlineMapIcon' });
    }
  };
  const resolve = createHeroiconResolver(outline);

  await resolve('MapIcon');
  await resolve('MapIcon');
  const icon = await resolve('MapIcon');

  assert.equal(callCount, 1);
  assert.equal(icon.name, 'OutlineMapIcon');
});

test('createHeroiconResolver caches a rejection too, instead of retrying on every call', async () => {
  const resolve = createHeroiconResolver({});

  await assert.rejects(() => resolve('NotARealIconName'));
  const secondAttempt = resolve('NotARealIconName');

  // Same cached promise instance is returned on the second call.
  assert.equal(resolve('NotARealIconName'), secondAttempt);
  await assert.rejects(() => secondAttempt);
});
