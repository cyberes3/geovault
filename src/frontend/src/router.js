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
        component: () => import('./components/import/ImportHomePage.vue'),
    },
    {
        path: '/import/upload',
        name: 'Import Data',
        component: () => import('./components/import/ImportUploadPage.vue'),
    },
    {
        path: '/import/process/:id',
        name: 'Process Data',
        component: () => import('./components/import/ImportProcessPage.vue'),
        props: true
    },
    {
        path: '/map',
        name: 'Map',
        component: () => import('./components/map/MapPage.vue'),
    },
    {
        path: '/mapshare',
        name: 'MapShare',
        component: () => import('./components/map/MapPage.vue'),
    },
    {
        path: '/tags',
        name: 'Tags',
        component: () => import('./components/tags/TagsPage.vue'),
    },
    {
        path: '/collections',
        name: 'Collections',
        component: () => import('./components/collections/CollectionsPage.vue'),
    },
    {
        path: '/settings',
        name: 'Settings',
        component: () => import('./components/settings/SettingsPage.vue'),
    },
    {
        path: '/admin',
        name: 'Admin',
        component: () => import('./components/admin/AdminPanelPage.vue'),
        meta: { requiresAdmin: true }
    }
    // Catch-all (NotFound) is added in main.js after extension routes so /extensions/* is matched first
]


const router = createRouter({
    history: createWebHashHistory(),
    routes
})

router.afterEach((to) => {
    if (to.path === '/mapshare' && to.query.id) {
        setGeoVaultPageTitle('Share')
        return
    }
    const label = to.meta.title ?? to.name ?? 'GeoVault'
    setGeoVaultPageTitle(label)
})

export default router
