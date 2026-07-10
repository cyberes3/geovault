import {createRouter, createWebHashHistory} from 'vue-router'
import {setGeoVaultPageTitle} from '@/utils/documentTitle.js'

const routes = [
    {
        path: '/',
        name: 'Root',
        meta: { title: 'Dashboard' },
        component: () => import('./components/dashboard/DashboardPage.vue'),
    },
    {
        path: '/dashboard',
        name: 'Dashboard',
        meta: { title: 'Dashboard' },
        component: () => import('./components/dashboard/DashboardPage.vue'),
    },
    {
        path: '/import',
        name: 'Import',
        meta: { title: 'Import Data' },
        component: () => import('./components/import/ImportHomePage.vue'),
    },
    {
        path: '/import/upload',
        name: 'Import Data',
        meta: { title: 'Upload Data' },
        component: () => import('./components/import/ImportUploadPage.vue'),
    },
    {
        path: '/import/process/:id',
        name: 'Process Data',
        meta: { title: 'Process Import' },
        component: () => import('./components/import/ImportProcessPage.vue'),
        props: true
    },
    {
        path: '/map',
        name: 'Map',
        meta: { title: 'Map' },
        component: () => import('./components/map/MapPage.vue'),
    },
    {
        path: '/mapshare',
        name: 'MapShare',
        meta: { title: 'Share' },
        component: () => import('./components/map/MapPage.vue'),
    },
    {
        path: '/tags',
        name: 'Tags',
        meta: { title: 'Tags' },
        component: () => import('./components/tags/TagsPage.vue'),
    },
    {
        path: '/collections',
        name: 'Collections',
        meta: { title: 'Collections' },
        component: () => import('./components/collections/CollectionsPage.vue'),
    },
    {
        path: '/settings',
        name: 'Settings',
        meta: { title: 'Settings' },
        component: () => import('./components/settings/SettingsPage.vue'),
    },
    {
        path: '/admin',
        name: 'Admin',
        meta: { title: 'Admin Panel', requiresAdmin: true },
        component: () => import('./components/admin/AdminPanelPage.vue'),
    }
    // Catch-all (NotFound) is added in main.js after extension routes so /extensions/* is matched first
]


const router = createRouter({
    history: createWebHashHistory(),
    routes
})

router.afterEach((to) => {
    const label = to.meta.title ?? 'GeoVault'
    setGeoVaultPageTitle(label)
})

export default router
