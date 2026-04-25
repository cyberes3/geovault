import { fetchConfig } from '@/utils/configService.js'

class UserStatus {
    constructor(authorized, email, id, featureCount = 0, tags = [], isSuperuser = false) {
        this.authorized = authorized;
        this.email = email;
        this.id = id;
        this.featureCount = featureCount;
        this.tags = tags;
        this.isSuperuser = isSuperuser;
    }
}

// Cache for in-flight getUserInfo requests to prevent duplicate concurrent calls
let getUserInfoPromise = null;

export async function getUserInfo() {
    // If a request is already in progress, return the same promise
    if (getUserInfoPromise) {
        return getUserInfoPromise;
    }
    
    // Create new promise and cache it
    getUserInfoPromise = (async () => {
        try {
            const response = await fetch('/api/user/status/')
            
            if (!response.ok) {
                if (response.status === 401) {
                    return new UserStatus(false, null, null, 0, [], false)
                }
                throw new Error(`HTTP error! status: ${response.status}`)
            }
            const userStatusData = await response.json()
            
            // Tags: array of { tag, count } from /api/user/status/
            let processedTags = []
            if (userStatusData.tags && Array.isArray(userStatusData.tags)) {
                processedTags = userStatusData.tags.map(tagObj => ({
                    tag: tagObj.tag,
                    count: tagObj.count,
                }))
            }
            
            return new UserStatus(userStatusData.authorized, userStatusData.email, userStatusData.id, userStatusData.featureCount, processedTags, userStatusData.is_superuser || false)
        } catch (error) {
            console.error(error)
            return null
        } finally {
            // Clear the cached promise when request completes (success or failure)
            getUserInfoPromise = null;
        }
    })();
    
    return getUserInfoPromise;
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