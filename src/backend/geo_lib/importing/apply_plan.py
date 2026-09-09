from dataclasses import dataclass
from typing import Any

from api.models import ImportQueue
from geo_lib.duplicates.skip_intent import SkipIntent


@dataclass(frozen=True)
class ApplyPlan:
    queue_id: int
    user_id: int
    import_custom_icons: bool
    skip_intent: SkipIntent
    bulk_operations: dict[str, Any]

    @classmethod
    def from_queue(
        cls,
        queue: ImportQueue,
        import_custom_icons: bool = True,
        extra_user_skipped: list[str] | None = None,
        extra_user_restored: list[str] | None = None,
    ) -> "ApplyPlan":
        intent = SkipIntent.from_stored(queue.skip_intent)
        if extra_user_skipped:
            intent.user_skipped.update(extra_user_skipped)
        if extra_user_restored:
            intent.user_restored_geometry.update(extra_user_restored)
            intent.auto_skipped_geometry -= intent.user_restored_geometry
        return cls(
            queue_id=queue.id,
            user_id=queue.user_id,
            import_custom_icons=import_custom_icons,
            skip_intent=intent,
            bulk_operations=queue.bulk_operations or {},
        )
