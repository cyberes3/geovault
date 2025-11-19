import {createRouter, createWebHashHistory} from 'vue-router'

const routes = [
    {
        path: '/',
        name: 'Home',
        component: () => import('./components/Home.vue'),
    },
    {
        path: '/dashboard',
        name: 'Dashboard',
        component: () => import('./components/dashboard/Dashboard.vue'),
    },
        {
        path: '/import',
        name: 'Import',
        component: () => import('./components/import/ImportHome.vue'),
    },
    {
        path: '/import/upload',
        name: 'Import Data',
        component: () => import('./components/import/ImportUpload.vue'),
    },
    {
        path: '/import/process/:id',
        name: 'Process Data',
        component: () => import('./components/import/ImportProcess.vue'),
        props: true
    },
    {
        path: '/map',
        name: 'Map',
        component: () => import('./components/map/GeoJsonMap.vue'),
    },
    {
        path: '/tags',
        name: 'Tags',
        component: () => import('./components/TagsPage.vue'),
    },
    {
        path: '/settings',
        name: 'Settings',
        component: () => import('./components/settings/Settings.vue'),
    }
]


const router = createRouter({
    history: createWebHashHistory(),
    routes
})
export default router
