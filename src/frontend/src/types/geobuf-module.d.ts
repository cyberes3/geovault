/**
 * Minimal ambient types for the untyped `geobuf` package (no upstream `@types/geobuf`).
 * `pbf` ships its own types and is imported directly where needed.
 */
declare module 'geobuf' {
    import type Pbf from 'pbf';

    export function encode(geojson: unknown, pbf: Pbf): Uint8Array;
    export function decode(pbf: Pbf): unknown;
}
