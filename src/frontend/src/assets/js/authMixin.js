import {UserInfo} from "@/assets/js/types/store-types";
import {getUserInfo} from "@/assets/js/auth.js";

export const authMixin = {
    async created() {
        // Check if userInfo already exists in store (set by App.vue)
        const existingUserInfo = this.$store.state.userInfo;
        
        if (existingUserInfo && existingUserInfo.email) {
            // User info already loaded, no need to make API call
            return;
        }
        
        // Only call API if store is empty
        const userStatus = await getUserInfo()
        if (!userStatus || !userStatus.authorized) {
            // Don't redirect here - App.vue handles auth redirects globally
            return;
        }
        const userInfo = new UserInfo(userStatus.email, userStatus.id, userStatus.featureCount, userStatus.tags || [])
        this.$store.commit('userInfo', userInfo)
    },
}
