/**
 * Minimal ambient types for the untyped `geobuf` package (no upstream `@types/geobuf`).
 * `pbf` ships its own types and is imported directly where needed.
 */
declare module 'geobuf' {
    import type { PbfReader } from 'pbf';

    export function encode(geojson: unknown, pbf: PbfReader): Uint8Array;
    export function decode(pbf: PbfReader): unknown;
}
