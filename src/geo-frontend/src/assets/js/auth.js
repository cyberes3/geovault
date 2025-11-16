import { getProtectedTags, fetchConfig } from '@/utils/configService.js'
import { filterProtectedTags } from '@/utils/tagUtils.js'

class UserStatus {
    constructor(authorized, username, id, featureCount = 0, tags = []) {
        this.authorized = authorized;
        this.username = username;
        this.id = id;
        this.featureCount = featureCount;
        this.tags = tags;
    }
}

export async function getUserInfo() {
    try {
        // Fetch config in parallel with user status
        const [response, protectedTags] = await Promise.all([
            fetch('/api/user/status/'),
            getProtectedTags()
        ])
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`)
        }
        const userStatusData = await response.json()
        
        // Filter protected tags from user tags
        const filteredTags = filterProtectedTags(userStatusData.tags || [], protectedTags)
        
        return new UserStatus(userStatusData.authorized, userStatusData.username, userStatusData.id, userStatusData.featureCount, filteredTags)
    } catch (error) {
        console.error(error)
        return null
    }
}


export function getCookie(name) {
    let cookieValue = null;
    if (document.cookie && document.cookie !== '') {
        const cookies = document.cookie.split(';');
        for (let i = 0; i < cookies.length; i++) {
            const cookie = cookies[i].trim(); // replaced jQuery.trim() with native JS trim()
            // Does this cookie string begin with the name we want?
            if (cookie.substring(0, name.length + 1) === (name + '=')) {
                cookieValue = decodeURIComponent(cookie.substring(name.length + 1));
                break;
            }
        }
    }
    return cookieValue;
}