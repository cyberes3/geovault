from typing import Dict, Any, Tuple, Optional

from pydantic import ValidationError

from api.validation.feature_updates import validate_pydantic_model, BulkOperationsPayload


def validate_bulk_operations_payload(bulk_ops: Dict[str, Any]) -> Tuple[bool, Optional[str]]:
    """
    Validate a bulk_operations payload used for styling/tagging.

    Enforces that only tags, colors, and icon fields can be changed and that
    values have the expected types.

    Args:
        bulk_ops: Dictionary from the request's bulk_operations field

    Returns:
        ``(is_valid, error_message)``. ``error_message`` is None when ``is_valid`` is True.
    """
    if not isinstance(bulk_ops, dict):
        return False, "bulk_operations must be a JSON object"

    # Use Pydantic validation
    try:
        validate_pydantic_model(BulkOperationsPayload, bulk_ops)
        return True, None
    except ValidationError:
        return False, "Invalid bulk operations format"
