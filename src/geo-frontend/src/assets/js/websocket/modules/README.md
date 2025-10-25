# WebSocket Modules Documentation

This directory contains WebSocket modules for the frontend realtime system. Each module handles a specific feature's realtime events.

## Creating a New Module

To create a new WebSocket module:

### 1. Create the Module File

Create a new file in this directory following the naming pattern: `[FeatureName]Module.js`

Example: `NotificationsModule.js`

### 2. Extend BaseModule

```javascript
import { BaseModule } from './BaseModule.js';

export class NotificationsModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'notifications'; // Must match backend module name
    }

    /**
     * Initialize the module when WebSocket connects
     * @param {RealtimeSocket} socket - The WebSocket service instance
     */
    initialize() {
        super.initialize();

        // Subscribe to events
        this.subscribe('notification_received', (data) => {
            console.log('Notification received:', data);
            this.store.dispatch('addNotification', data);
        });

        this.subscribe('notification_read', (data) => {
            console.log('Notification read:', data);
            this.store.dispatch('markNotificationRead', data.id);
        });
    }

    /**
     * Cleanup the module when WebSocket disconnects
     */
    cleanup() {
        super.cleanup();
        console.log('Notifications module cleaned up');
    }
}
```

### 3. Register the Module

Add your module to the registry in `ModuleRegistry.js`:

```javascript
// In ModuleRegistry.js
import {NotificationsModule} from './NotificationsModule.js';

export const MODULE_REGISTRY = [
    ImportJobModule,
    NotificationsModule, // Add your module here
    // Add more modules as needed
];
```

**That's it!** The module will be automatically loaded when the app starts. No need to manually register it in `App.vue`.

## Dynamic Module Loading

The system now supports automatic module discovery and loading:

1. **Module Registry**: All modules are registered in `ModuleRegistry.js`
2. **Automatic Loading**: Modules are loaded automatically when the app starts
3. **No Manual Registration**: No need to manually register modules in `App.vue`

### How It Works

1. When `App.vue` calls `realtimeSocket.loadAllModules(store)`, the system:
   - Imports the `ModuleRegistry.js` file
   - Iterates through all registered module classes
   - Instantiates each module with the Vuex store
   - Validates each module
   - Registers them with the WebSocket service

2. If a module fails to load, it's skipped and an error is logged
3. All successfully loaded modules are automatically initialized when the WebSocket connects

## Module Lifecycle

1. **Constructor**: Called when module is created
2. **initialize()**: Called when WebSocket connects
3. **cleanup()**: Called when WebSocket disconnects

## Available Methods

### From BaseModule

- `subscribe(socket, event, handler)` - Subscribe to an event and track for cleanup
- `unsubscribeAll(socket)` - Unsubscribe all tracked subscriptions
- `send(socket, type, data)` - Send a message to the server
- `requestRefresh(socket)` - Request a refresh of module data

### Module Properties

- `this.store` - Vuex store instance
- `this.moduleName` - Module name (must match backend)
- `this.subscriptions` - Array of tracked subscriptions

## Best Practices

1. **Always call `super.initialize()` and `super.cleanup()`** in your overridden methods
2. **Use `this.subscribe()`** instead of direct socket subscriptions for automatic cleanup
3. **Handle errors gracefully** in event handlers
4. **Use descriptive console logs** for debugging
5. **Keep module logic focused** on a single feature
6. **Update Vuex store** through actions, not direct mutations

## Example: Complete Module

```javascript
import { BaseModule } from './BaseModule.js';

export class ChatModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'chat';
    }

    initialize() {
        super.initialize();

        // Handle new messages
        this.subscribe('message_received', (data) => {
            this.store.dispatch('addChatMessage', data);
        });

        // Handle user typing
        this.subscribe('user_typing', (data) => {
            this.store.dispatch('setUserTyping', data);
        });

        // Handle user stopped typing
        this.subscribe('user_stopped_typing', (data) => {
            this.store.dispatch('clearUserTyping', data.userId);
        });
    }

    cleanup() {
        super.cleanup();
        console.log('Chat module cleaned up');
    }

    // Custom method for sending messages
    sendMessage(message) {
        this.send('send_message', { message });
    }
}
```

Then add it to the registry:

```javascript
// In ModuleRegistry.js
import {ChatModule} from './ChatModule.js';

export const MODULE_REGISTRY = [
    ImportJobModule,
    ChatModule, // Add your new module here
    // Add more modules as needed
];
```

## Backend Integration

Make sure your backend module:

1. Has the same `module_name` as your frontend module
2. Handles the events you're subscribing to
3. Sends data in the expected format
4. Uses the correct channel layer group names (`realtime_{user_id}`)
