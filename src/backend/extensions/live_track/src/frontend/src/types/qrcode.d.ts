/**
 * Minimal ambient types for the untyped `qrcode` package. Only the subset used by
 * `GpsLoggerInstructionsModal.vue` (rendering a data URL) is declared.
 */
declare module 'qrcode' {
    export interface QRCodeToDataURLOptions {
        width?: number;
        margin?: number;
    }

    export function toDataURL(text: string, options?: QRCodeToDataURLOptions): Promise<string>;

    const QRCode: {
        toDataURL: typeof toDataURL;
    };
    export default QRCode;
}
