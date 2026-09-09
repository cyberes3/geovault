export class ShareSessionError extends Error {
    readonly status: 'invalid' | 'failed';

    constructor(message: string, status: 'invalid' | 'failed' = 'invalid') {
        super(message);
        this.name = 'ShareSessionError';
        this.status = status;
    }
}
