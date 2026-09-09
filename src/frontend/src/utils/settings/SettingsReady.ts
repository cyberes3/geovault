export type SettingsReadyStatus = 'idle' | 'loading' | 'ready' | 'error';

/**
 * One in-flight user-settings load. Maps and extensions await this instead of polling.
 * Failure leaves prior settings in place and never commits `{}`.
 */
export class SettingsReady {
    status: SettingsReadyStatus = 'idle';
    private inflight: Promise<void> | null = null;

    reset(): void {
        this.status = 'idle';
        this.inflight = null;
    }

    run(load: () => Promise<void>): Promise<void> {
        if (this.inflight) {
            return this.inflight;
        }
        this.status = 'loading';
        this.inflight = load()
            .then(() => {
                this.status = 'ready';
            })
            .catch((error) => {
                this.status = 'error';
                throw error;
            })
            .finally(() => {
                this.inflight = null;
            });
        return this.inflight;
    }

    awaitReady(start: () => Promise<void>): Promise<void> {
        if (this.status === 'ready' || this.status === 'error') {
            return Promise.resolve();
        }
        if (this.inflight) {
            return this.inflight.catch(() => undefined);
        }
        return start().catch(() => undefined);
    }
}

export const settingsReady = new SettingsReady();
