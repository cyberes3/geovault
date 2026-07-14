/**
 * Minimal ambient types for the untyped `piexifjs` package. Only the subset used by
 * `Geotagger.vue` (loading/dumping EXIF, reading/writing GPS tags) is declared.
 */
declare module 'piexifjs' {
    export type Rational = [number, number];

    export interface ExifObject {
        '0th': Record<number, unknown>;
        Exif: Record<number, unknown>;
        GPS: Record<number, unknown>;
        Interop: Record<number, unknown>;
        '1st': Record<number, unknown>;
        thumbnail: string | null;
    }

    export const GPSIFD: {
        GPSLatitudeRef: number;
        GPSLatitude: number;
        GPSLongitudeRef: number;
        GPSLongitude: number;
    };

    export function load(jpegDataUrl: string): ExifObject;
    export function dump(exifObj: ExifObject): string;
    export function insert(exifBytes: string, jpegDataUrl: string): string;

    const piexif: {
        GPSIFD: typeof GPSIFD;
        load: typeof load;
        dump: typeof dump;
        insert: typeof insert;
    };
    export default piexif;
}
