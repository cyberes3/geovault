import pytest

from geo_lib.perf.query_budget import QueryBudget, QueryBudgetError


class TestQueryBudget:
    def test_rejects_non_positive(self):
        with pytest.raises(QueryBudgetError):
            QueryBudget(0)
        with pytest.raises(QueryBudgetError):
            QueryBudget(-1)

    def test_clamp_none_returns_max(self):
        budget = QueryBudget(5000)
        assert budget.clamp(None) == 5000

    def test_clamp_caps_requested(self):
        budget = QueryBudget(5000)
        assert budget.clamp(100) == 100
        assert budget.clamp(9000) == 5000

    def test_clamp_rejects_non_positive_request(self):
        budget = QueryBudget(5000)
        with pytest.raises(QueryBudgetError):
            budget.clamp(0)
