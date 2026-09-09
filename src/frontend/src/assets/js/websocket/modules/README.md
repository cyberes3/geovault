# WebSocket Modules

This directory contains the modules for `realtimeSocket` (`assets/js/websocket/realtimeSocket.ts`),
the app-lifetime `GeoVaultSocket` connection to `/ws/realtime/`. Every message on that connection
is multiplexed with a `{ module, type, data }` envelope; each module here owns one `module` name
and reacts to its `type`s, mirroring a same-named module class on the backend
(`geo_lib/websocket/modules/*.py`).

Currently registered modules (see `ModuleRegistry.ts`):

| Module | `module_name` | Backend counterpart |
| --- | --- | --- |
| `ImportQueueModule` | `import_queue` | `geo_lib/websocket/modules/import_queue_module.py` |
| `ImportHistoryModule` | `import_history` | (broadcasts only, see `import_operations/websocket.py`) |
| `ProcessJobModule` | `process_job` | `geo_lib/websocket/modules/process_job_module.py` |
| `DeleteJobModule` | `delete_job` | `geo_lib/websocket/modules/delete_job_module.py` |
| `BulkImportJobModule` | `bulk_import_job` | `geo_lib/websocket/modules/bulk_import_job_module.py` |
| `BulkDeleteJobModule` | `bulk_delete_job` | `geo_lib/websocket/modules/bulk_delete_job_module.py` |

`ImportProcessPage.vue` and live-track each construct their own `GeoVaultSocket` for a
different URL (`/ws/upload/status/:id/` and `/ws/extensions/live-track/trackers-live/`).
Those sockets share heartbeat, reconnect, and handler-map behavior with this connection;
they do not share the multiplexed module registry.

## Creating a new module

1. Create `[FeatureName]Module.ts` in this directory, extending `BaseModule`:

```typescript
import { BaseModule } from './BaseModule';

export class NotificationsModule extends BaseModule {
    readonly moduleName = 'notifications'; // Must match the backend module_name

    protected onInitialize(): void {
        this.subscribe('notification_received', (data) => {
            this.store.dispatch('notifications/addNotification', data);
        });

        this.subscribe('notification_read', (data) => {
            this.store.dispatch('notifications/markNotificationRead', data.id);
        });
    }
}
```

2. Register it in `ModuleRegistry.ts`:

```typescript
import { NotificationsModule } from './NotificationsModule';

export const MODULE_REGISTRY: ModuleConstructor[] = [
    // ...existing modules,
    NotificationsModule,
];
```

That's it -- `realtimeSocket.loadAllModules(store)` (called once from `App.vue`) instantiates and
registers every class in `MODULE_REGISTRY` automatically; nothing else needs to know about it.

## Module lifecycle

1. **Constructor** (`BaseModule`) -- stores the Vuex store reference. `this.socket` is not yet set.
2. **`initialize()`** -- called once when the socket registers the module. It invokes
   `onInitialize()` exactly once; subscribe to events there. Reconnects do not re-run this.
3. **`cleanup()`** -- called on logout / unregister, not on a transient disconnect. `subscribe`d
   handlers are unsubscribed here.

## API available to a module (via `BaseModule`)

- `this.store` -- the Vuex store. Modules should only `dispatch` actions, never `commit` directly
  or reach into `state` -- keep the getters/actions of each Vuex module as the only public API.
- `this.subscribe(event, handler)` -- subscribe to `{module: this.moduleName, type: event}`
  messages.
- `this.send(type, data)` -- send `{module: this.moduleName, type, data}` to the server.
- `this.requestRefresh()` -- shorthand for `send('refresh')`.
- `this.socket` -- the realtime facade (typed as `RealtimeSocketLike`). Use this
  directly only for cross-module operations, e.g. `ProcessJobModule` calls
  `this.socket.requestRefresh('import_queue')` because it has no state of its own to refresh.

## Best practices

1. Override `onInitialize()` (not `initialize()`) to subscribe. `cleanup()` already unsubscribes.
2. Only `dispatch` Vuex actions from a module; let the target store module decide how to mutate
   its own state.
3. If a status event doesn't carry enough data to patch a single row (e.g. the server-computed
   feature count after processing completes), request a refresh rather than guessing -- and leave
   a comment explaining why a full re-fetch is used instead of a targeted patch.
4. Keep module logic focused on one feature/backend module; compose shared logic (e.g.
   `jobStatusHelpers.ts`) into pure helper functions rather than a deeper class hierarchy.

## Backend contract

For a module to receive anything, the backend module must:

1. Use the same `module_name` string.
2. Send `{ type, data }` payloads that the frontend module's `subscribe()` calls expect.
3. Broadcast through the `realtime_{user_id}` channel-layer group.
