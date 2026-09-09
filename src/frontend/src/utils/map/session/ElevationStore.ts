import type { VaultFeature } from '@/contracts/feature';
import { canonicalFeatureId } from '@/utils/map/common/featureIdentity';
import { getCoordinatesFromGeometry } from '@/utils/map/featureExtent';

function coordGeneration(coordinates: unknown[]): string {
    return String(coordinates.length);
}

function readZ(coord: unknown): number | null {
    if (!Array.isArray(coord) || coord.length < 3) return null;
    const value = Number(coord[2]);
    return Number.isFinite(value) ? value : null;
}

/**
 * Elevations stay aligned with coordinates. Missing Z is kept as null — never sparse-filtered.
 */
export class ElevationStore {
    private readonly byId = new Map<string, { coordGeneration: string; values: Array<number | null> }>();

    capture(feature: VaultFeature): void {
        const id = canonicalFeatureId(feature);
        if (!id || !feature.geometry) return;
        const coords = getCoordinatesFromGeometry(feature.geometry as never);
        this.byId.set(id, {
            coordGeneration: coordGeneration(coords),
            values: coords.map(readZ),
        });
    }

    restoreInto(feature: VaultFeature): void {
        const id = canonicalFeatureId(feature);
        if (!id || !feature.geometry) return;
        const stored = this.byId.get(id);
        if (!stored) return;
        const coords = getCoordinatesFromGeometry(feature.geometry as never);
        if (coordGeneration(coords) !== stored.coordGeneration) return;
        if (coords.length !== stored.values.length) return;

        const writeZ = (coord: unknown, index: number): unknown => {
            const z = stored.values[index];
            if (z == null || !Array.isArray(coord)) return coord;
            if (coord.length >= 3) {
                coord[2] = z;
                return coord;
            }
            return [...coord, z];
        };

        const geometry = feature.geometry as { type: string; coordinates?: unknown };
        if (geometry.type === 'Point' && Array.isArray(geometry.coordinates)) {
            geometry.coordinates = writeZ(geometry.coordinates, 0);
            return;
        }
        if ((geometry.type === 'LineString' || geometry.type === 'MultiPoint') && Array.isArray(geometry.coordinates)) {
            geometry.coordinates = geometry.coordinates.map((coord, index) => writeZ(coord, index));
        }
    }

    delete(id: string): void {
        this.byId.delete(String(id));
    }

    clear(): void {
        this.byId.clear();
    }
}
