import {UserInfo} from "@/assets/js/types/store-types";
import {getUserInfo} from "@/assets/js/auth.js";

export const authMixin = {
    async created() {
        const userStatus = await getUserInfo()
        if (!userStatus.authorized) {
            window.location = "/account/login/"
        }
        const userInfo = new UserInfo(userStatus.username, userStatus.id, userStatus.featureCount)
        this.$store.commit('userInfo', userInfo)
    },
}
