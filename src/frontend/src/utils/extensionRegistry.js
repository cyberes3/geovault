import { reactive, markRaw } from 'vue';

/**
 * Registry for managing dynamic UI components and routes from extensions.
 */
export const extensionRegistry = reactive({
  navLinks: [], // Array of { label, path, component }
  tools: [], // Array of { label, fullPath, icon }
  settingsTabs: [], // Array of { label, id, component, icon }
  routes: [], // Array of raw routes (to be scoped)

  /**
   * Register a link for the top navigation bar.
   */
  registerNavLink(link) {
    if (link.component) link.component = markRaw(link.component);
    this.navLinks.push(link);
  },

  /**
   * Register a tool for the "Tools" dropdown.
   */
  registerTool(tool) {
    if (tool.icon) tool.icon = markRaw(tool.icon);
    this.tools.push(tool);
  },

  /**
   * Register a new section in the User Settings page.
   */
  registerSettingsTab(tab) {
    if (tab.component) tab.component = markRaw(tab.component);
    if (tab.icon) tab.icon = markRaw(tab.icon);
    this.settingsTabs.push(tab);
  },

  /**
   * Register router paths. 
   * Handled by the loader to ensure scoping under /extensions/<name>/
   */
  registerRoutes(routes) {
    routes.forEach(route => {
      if (route.component) route.component = markRaw(route.component);
    });
    this.routes.push(...routes);
  },

  /**
   * Shared utilities for extensions
   */
  utils: {
    updateUserSetting: null, // Set by loader
    loadSettingsFromStore: null, // Set by loader
    keyValueToNested: null, // Set by loader
    getNestedValue: null // Set by loader
  },
  toast: null // Set by loader
});
