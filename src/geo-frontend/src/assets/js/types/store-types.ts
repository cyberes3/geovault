import {getCookie} from "../auth.js"

export class UserInfo {
    username: string;
    id: bigint;
    featureCount: number;
    csrftoken: string | null;

    constructor(username: string, userId: bigint, featureCount: number = 0) {
        this.username = username
        this.id = userId
        this.featureCount = featureCount
        this.csrftoken = getCookie("csrftoken")
    }
}