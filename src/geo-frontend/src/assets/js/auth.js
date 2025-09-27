class UserStatus {
    constructor(authorized, username, id, featureCount = 0) {
        this.authorized = authorized;
        this.username = username;
        this.id = id;
        this.featureCount = featureCount;
    }
}

export async function getUserInfo() {
    try {
        const response = await fetch('/api/user/status/')
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`)
        }
        const userStatusData = await response.json()
        return new UserStatus(userStatusData.authorized, userStatusData.username, userStatusData.id, userStatusData.featureCount)
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