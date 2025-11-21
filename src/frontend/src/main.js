import './assets/css/main.css'

import {createApp} from 'vue'
import App from './App.vue'
import store from "@/assets/js/store.ts";
import router from "@/router.js";
import '@/assets/css/root.css'

createApp(App)
    .use(router)
    .use(store)
    .mount('#app')
