import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { origin: 'http://localhost' } };

const {
    FEATURE_LAYER_CONFIGS,
    createZoomBasedRadiusExpression,
    getPointIconLayerConfig,
    getPointLayerConfig,
    getReplacementPointLayerConfig,
} = await import('./featureLayerSpec.ts');

const CAMERA_OPS = new Set(['zoom', 'pitch', 'distance-from-center']);

function visit(node, visitFn) {
    visitFn(node);
    if (!Array.isArray(node)) return;
    for (const child of node) visit(child, visitFn);
}

function cameraOpIsNested(expression, opName) {
    if (!Array.isArray(expression)) return false;
    const [op, , input] = expression;
    const isInput = Array.isArray(input) && input[0] === opName;
    const topLevelAllowed = (op === 'interpolate' || op === 'step') && isInput;
    let nested = false;
    visit(expression, (node) => {
        if (Array.isArray(node) && node[0] === opName && node !== input) nested = true;
    });
    if (isInput && !topLevelAllowed) return true;
    return nested;
}

function zoomIsNested(expression) {
    return cameraOpIsNested(expression, 'zoom');
}

function containsFeatureState(node) {
    let found = false;
    visit(node, (value) => {
        if (Array.isArray(value) && value[0] === 'feature-state') found = true;
    });
    return found;
}

test('circle-radius uses zoom only as the top-level interpolate input', () => {
    const radius = createZoomBasedRadiusExpression(4, 2);
    assert.equal(radius[0], 'interpolate');
    assert.deepEqual(radius[2], ['zoom']);
    assert.equal(zoomIsNested(radius), false);
    assert.equal(containsFeatureState(radius), true);

    const points = getPointLayerConfig();
    assert.equal(points.paint['circle-radius'][0], 'interpolate');
    assert.equal(zoomIsNested(points.paint['circle-radius']), false);

    const replacements = getReplacementPointLayerConfig();
    assert.equal(replacements.paint['circle-radius'][0], 'interpolate');
    assert.equal(zoomIsNested(replacements.paint['circle-radius']), false);
});

test('point-icons keep feature-state out of layout and highlight through paint', () => {
    const icons = getPointIconLayerConfig();
    assert.equal(containsFeatureState(icons.layout), false);
    assert.equal(icons.layout['icon-size'], 1);
    assert.equal(containsFeatureState(icons.paint['icon-halo-width']), true);
});

test('every feature layer keeps camera ops top-level and feature-state out of layout and filters', () => {
    for (const [id, factory] of Object.entries(FEATURE_LAYER_CONFIGS)) {
        const layer = factory();
        const expressions = [layer.filter, ...Object.values(layer.paint ?? {}), ...Object.values(layer.layout ?? {})];
        for (const expression of expressions) {
            for (const opName of CAMERA_OPS) {
                assert.equal(
                    cameraOpIsNested(expression, opName),
                    false,
                    `${id} nests "${opName}" outside a top-level interpolate/step`,
                );
            }
        }
        assert.equal(containsFeatureState(layer.layout), false, `${id} layout uses feature-state`);
        assert.equal(containsFeatureState(layer.filter), false, `${id} filter uses feature-state`);
    }
});
