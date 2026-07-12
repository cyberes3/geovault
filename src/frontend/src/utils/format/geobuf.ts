/**
 * Geobuf format utilities for decoding protobuf-encoded GeoJSON responses
 * and extracting metadata from HTTP headers.
 */

import geobuf from 'geobuf';
import Pbf from 'pbf';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import { ApiError } from '@/utils/apiError';

/** Metadata fields extracted from `X-*` response headers alongside a bbox payload. */
export type BboxMetadata = Record<string, string | number | boolean>;

export interface BboxResponseData {
    data: GeoJsonFeatureCollection;
    /** Coerced `X-*` header metadata alongside `data` (see `BboxMetadata`). */
    [key: string]: string | number | boolean | GeoJsonFeatureCollection;
}

const METADATA_HEADER_NAMES = [
    'x-feature-count',
    'x-total-features-in-bbox',
    'x-max-features-limit',
    'x-zoom-level',
    'x-fallback-used',
    'x-timestamp',
] as const;

function isProtobufContentType(contentType: string): boolean {
    return contentType.includes('application/x-protobuf') || contentType.includes('application/vnd.mapbox-vector-tile');
}

/** Decode geobuf binary data to a GeoJSON FeatureCollection. */
export function decodeGeobuf(arrayBuffer: ArrayBuffer | Uint8Array): GeoJsonFeatureCollection {
    const pbf = new Pbf(new Uint8Array(arrayBuffer));
    return geobuf.decode(pbf) as GeoJsonFeatureCollection;
}

function headerNameToMetadataKey(headerName: string): string {
    return headerName
        .substring(2) // Remove 'x-'
        .toLowerCase()
        .replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())
        .replace(/^[a-z]/, (letter: string) => letter.toLowerCase())
        // Convert camelCase to snake_case
        .replace(/([A-Z])/g, '_$1')
        .toLowerCase();
}

function coerceHeaderValue(headerValue: string): string | number | boolean {
    const numValue = parseFloat(headerValue);
    if (!isNaN(numValue) && isFinite(numValue) && headerValue.trim() !== '') {
        return numValue;
    }
    if (headerValue === 'true') return true;
    if (headerValue === 'false') return false;
    return headerValue;
}

/**
 * Extract metadata from a lowercase-keyed header record and reconstruct the response format.
 * Accepts a plain `Record<string, string>` so both `fetch()` `Headers` and axios `AxiosHeaders`
 * (via `.toJSON(true)`) can share the same implementation.
 */
export function extractMetadataFromHeaders(headers: Record<string, string>, geojsonData: GeoJsonFeatureCollection): BboxResponseData {
    const metadata: BboxMetadata = {};

    for (const [rawHeaderName, headerValue] of Object.entries(headers)) {
        const headerName = rawHeaderName.toLowerCase();
        if (!headerName.startsWith('x-')) continue;

        const key = headerNameToMetadataKey(headerName);
        if (headerName === 'x-fallback-used') {
            metadata[key] = headerValue === 'true';
        } else if (headerName === 'x-timestamp') {
            metadata[key] = parseFloat(headerValue);
        } else if ((METADATA_HEADER_NAMES as readonly string[]).includes(headerName)) {
            metadata[key] = parseInt(headerValue, 10);
        } else {
            metadata[key] = coerceHeaderValue(headerValue);
        }
    }

    return {
        data: geojsonData,
        ...metadata,
    };
}

/** Parse bbox response from `fetch()`, handling both JSON and geobuf formats. */
export async function parseBboxResponse(response: Response): Promise<BboxResponseData> {
    const contentType = response.headers.get('Content-Type') ?? '';

    if (isProtobufContentType(contentType)) {
        const arrayBuffer = await response.arrayBuffer();
        const geojsonData = decodeGeobuf(arrayBuffer);
        const headerRecord = Object.fromEntries(response.headers.entries());
        return extractMetadataFromHeaders(headerRecord, geojsonData);
    }
    return (await response.json()) as BboxResponseData;
}

/**
 * Parse a bbox response fetched via axios with `responseType: 'arraybuffer'`, handling both
 * JSON and geobuf formats. Used by `featuresApi`/`sharingApi` bbox endpoints so all network
 * calls flow through the shared `httpClient` axios instance instead of raw `fetch()`.
 */
export function parseBboxAxiosResponse(contentType: string, headers: Record<string, string>, arrayBuffer: ArrayBuffer): BboxResponseData {
    if (isProtobufContentType(contentType)) {
        const geojsonData = decodeGeobuf(arrayBuffer);
        return extractMetadataFromHeaders(headers, geojsonData);
    }
    const text = new TextDecoder().decode(arrayBuffer);
    return JSON.parse(text) as BboxResponseData;
}

/**
 * Decodes an axios `responseType: 'arraybuffer'` error response body (always JSON, since error
 * responses are never geobuf-encoded) so callers get the backend's actual error message instead
 * of a generic one produced by `ApiError.from` (which does not expect an `ArrayBuffer` body).
 */
export function normalizeBboxError(error: unknown): ApiError {
    const apiError = ApiError.from(error);
    if (apiError.data instanceof ArrayBuffer) {
        try {
            const text = new TextDecoder().decode(apiError.data);
            const parsed = JSON.parse(text) as { error?: string; message?: string };
            const message = parsed.error ?? parsed.message;
            if (message) {
                return new ApiError(message, { status: apiError.status, data: parsed, cause: apiError.cause });
            }
        } catch {
            // Fall through to the generic ApiError below.
        }
    }
    return apiError;
}
