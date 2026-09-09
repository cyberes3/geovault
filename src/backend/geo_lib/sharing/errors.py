from geo_lib.sharing.constants import INVALID_SHARE_LINK


class ShareError(Exception):
    def __init__(self, message: str, code: int = 400):
        super().__init__(message)
        self.message = message
        self.code = code


class InvalidShareLink(ShareError):
    def __init__(self, message: str = INVALID_SHARE_LINK):
        super().__init__(message, 404)


class ShareNotFound(ShareError):
    def __init__(self, message: str = "Share not found"):
        super().__init__(message, 404)


class ShareConflict(ShareError):
    def __init__(self, message: str = "Share already exists"):
        super().__init__(message, 409)


class ShareForbidden(ShareError):
    def __init__(self, message: str = "Access denied"):
        super().__init__(message, 403)


class ShareUnauthorized(ShareError):
    def __init__(self, message: str = "Unauthorized"):
        super().__init__(message, 401)
