import {getCookie} from "../auth.js"

export class UserInfo {
    username: String;
    id: BigInteger;
    featureCount: Number;
    csrftoken: String;

    constructor(username: String, userId: BigInteger, featureCount: Number = 0) {
        this.username = username
        this.id = userId
        this.featureCount = featureCount
        this.csrftoken = getCookie("csrftoken")
    }
}