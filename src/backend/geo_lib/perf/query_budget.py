"""Positive feature-query cap. Unlimited (-1) is not a valid budget."""


class QueryBudgetError(ValueError):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class QueryBudget:
    def __init__(self, max_features: int):
        if not isinstance(max_features, int) or max_features <= 0:
            raise QueryBudgetError("max_features must be a positive integer")
        self.max_features = max_features

    def clamp(self, requested: int | None) -> int:
        if requested is None:
            return self.max_features
        if requested <= 0:
            raise QueryBudgetError("requested limit must be a positive integer")
        return min(requested, self.max_features)
