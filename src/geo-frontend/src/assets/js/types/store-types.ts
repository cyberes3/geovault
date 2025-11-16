import {getCookie} from "../auth.js"

export class UserInfo {
    username: string;
    id: bigint;
    featureCount: number;
    tags: string[];
    csrftoken: string | null;

    constructor(username: string, userId: bigint, featureCount: number = 0, tags: string[] = []) {
        this.username = username
        this.id = userId
        this.featureCount = featureCount
        this.tags = tags
        this.csrftoken = getCookie("csrftoken")
    }
}