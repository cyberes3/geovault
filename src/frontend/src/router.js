import {createRouter, createWebHashHistory} from 'vue-router'

const routes = [
    {
        path: '/',
        redirect: '/dashboard'
    },
    {
        path: '/dashboard',
        name: 'Dashboard',
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
    },
    {
        path: '/:pathMatch(.*)*',
        name: 'NotFound',
        component: () => import('./components/NotFoundPage.vue'),
    }
]


const router = createRouter({
    history: createWebHashHistory(),
    routes
})
export default router
