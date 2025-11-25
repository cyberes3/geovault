import {getCookie} from "../auth.js"

export class UserInfo {
    email: string | null;
    id: bigint;
    featureCount: number;
    tags: string[];
    csrftoken: string | null;
    isSuperuser: boolean;

    constructor(email: string | null, userId: bigint, featureCount: number = 0, tags: string[] = [], isSuperuser: boolean = false) {
        this.email = email
        this.id = userId
        this.featureCount = featureCount
        this.tags = tags
        this.csrftoken = getCookie("csrftoken")
        this.isSuperuser = isSuperuser
    }
}