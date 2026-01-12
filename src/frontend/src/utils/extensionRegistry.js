import { reactive } from 'vue';

/**
 * Registry for managing dynamic UI components and routes from extensions.
 */
export const extensionRegistry = reactive({
  navLinks: [], // Array of { label, path, component }
  settingsTabs: [], // Array of { label, id, component, icon }
  routes: [], // Array of raw routes (to be scoped)
  
  /**
   * Register a link for the top navigation bar.
   */
  registerNavLink(link) {
    this.navLinks.push(link);
  },
  
  /**
   * Register a new section in the User Settings page.
   */
  registerSettingsTab(tab) {
    this.settingsTabs.push(tab);
  },
  
  /**
   * Register router paths. 
   * Handled by the loader to ensure scoping under /extensions/<name>/
   */
  registerRoutes(routes) {
    this.routes.push(...routes);
  }
});
