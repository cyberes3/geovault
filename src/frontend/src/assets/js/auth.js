import { fetchConfig } from '@/utils/configService.js'

class UserStatus {
    constructor(authorized, email, id, featureCount = 0, tags = []) {
        this.authorized = authorized;
        this.email = email;
        this.id = id;
        this.featureCount = featureCount;
        this.tags = tags;
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
                throw new Error(`HTTP error! status: ${response.status}`)
            }
            const userStatusData = await response.json()
            
            // Handle new tag structure (array of objects with tag and count) or legacy (array of strings)
            // Tags are already separated - backend only returns user tags
            let processedTags = []
            if (userStatusData.tags && Array.isArray(userStatusData.tags)) {
                if (userStatusData.tags.length > 0 && typeof userStatusData.tags[0] === 'object' && 'tag' in userStatusData.tags[0]) {
                    // New structure: array of objects with tag and count
                    processedTags = userStatusData.tags.map(tagObj => ({ tag: tagObj.tag, count: tagObj.count }))
                } else {
                    // Legacy structure: array of strings
                    processedTags = userStatusData.tags.map(tag => ({ tag: tag, count: 0 }))
                }
            }
            
            return new UserStatus(userStatusData.authorized, userStatusData.email, userStatusData.id, userStatusData.featureCount, processedTags)
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