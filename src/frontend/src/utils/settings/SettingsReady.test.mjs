import test from 'node:test';
import assert from 'node:assert/strict';
import { SettingsReady } from './SettingsReady.ts';

test('SettingsReady is a single in-flight promise and does not commit on failure', async () => {
    const ready = new SettingsReady();
    let loads = 0;
    const load = () => {
        loads += 1;
        return new Promise((resolve) => {
            setTimeout(resolve, 10);
        });
    };

    const first = ready.run(load);
    const second = ready.run(load);
    assert.equal(ready.status, 'loading');
    await Promise.all([first, second]);
    assert.equal(loads, 1);
    assert.equal(ready.status, 'ready');
    await ready.awaitReady(load);
    assert.equal(loads, 1);
});

test('SettingsReady records error and awaitReady does not retry', async () => {
    const ready = new SettingsReady();
    await assert.rejects(() => ready.run(async () => {
        throw new Error('boom');
    }));
    assert.equal(ready.status, 'error');
    let started = false;
    await ready.awaitReady(async () => {
        started = true;
    });
    assert.equal(started, false);
});
