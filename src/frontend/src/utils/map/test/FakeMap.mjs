/**
 * In-memory MapLibre stand-in for node:test. No WebGL.
 */
export function createFakeMap(options = {}) {
    const layers = [];
    const sources = new Map();
    const images = new Set();
    const featureState = new Map();
    let missingResolver = null;
    const projectFn = options.project ?? ((lngLat) => {
        const pair = Array.isArray(lngLat) ? lngLat : [lngLat.lng, lngLat.lat];
        return { x: pair[0] * 10, y: pair[1] * 10 };
    });

    function stateKey(feature) {
        return `${feature.source}:${feature.id}`;
    }

    return {
        layers,
        sources,
        images,
        featureState,
        queryFeatures: options.queryFeatures ?? [],
        _missingResolver: () => missingResolver,
        getZoom: () => options.zoom ?? 10,
        getContainer: () => options.container ?? { clientWidth: 800, clientHeight: 600 },
        getCanvas: () => options.canvas ?? { style: { cursor: '' } },
        getSource(id) {
            return sources.get(id);
        },
        addSource(id, spec) {
            sources.set(id, {
                ...spec,
                setData(data) {
                    this.data = data;
                },
            });
        },
        removeSource(id) {
            sources.delete(id);
        },
        getLayer(id) {
            return layers.find((layer) => layer.id === id);
        },
        addLayer(config, beforeId) {
            const index = beforeId ? layers.findIndex((layer) => layer.id === beforeId) : -1;
            if (index >= 0) {
                layers.splice(index, 0, config);
            } else {
                layers.push(config);
            }
        },
        removeLayer(id) {
            const index = layers.findIndex((layer) => layer.id === id);
            if (index >= 0) layers.splice(index, 1);
        },
        moveLayer(id, beforeId) {
            const index = layers.findIndex((layer) => layer.id === id);
            if (index < 0) return;
            const [layer] = layers.splice(index, 1);
            if (!beforeId) {
                layers.push(layer);
                return;
            }
            const before = layers.findIndex((item) => item.id === beforeId);
            layers.splice(before >= 0 ? before : layers.length, 0, layer);
        },
        getStyle() {
            return { layers: [...layers] };
        },
        setFilter() {},
        setLayoutProperty() {},
        setPaintProperty() {},
        hasImage(id) {
            return images.has(id);
        },
        addImage(id) {
            if (images.has(id)) {
                throw new Error('An image with this name already exists');
            }
            images.add(id);
        },
        setMissingStyleImageResolver(resolver) {
            missingResolver = resolver;
        },
        setFeatureState(feature, state) {
            const key = stateKey(feature);
            featureState.set(key, { ...featureState.get(key), ...state });
        },
        getFeatureState(feature) {
            return featureState.get(stateKey(feature)) ?? {};
        },
        removeFeatureState(feature, key) {
            const stateKeyValue = stateKey(feature);
            if (!key) {
                featureState.delete(stateKeyValue);
                return;
            }
            const current = featureState.get(stateKeyValue);
            if (current) delete current[key];
        },
        project(lngLat) {
            return projectFn(lngLat);
        },
        queryRenderedFeatures(_geometry, queryOptions = {}) {
            const hits = this.queryFeatures;
            if (!queryOptions.layers) return hits;
            return hits.filter((feature) => !feature._layer || queryOptions.layers.includes(feature._layer));
        },
    };
}
