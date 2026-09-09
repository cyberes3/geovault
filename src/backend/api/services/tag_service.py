"""Django adapter for the tag kernel."""

from api.models import FeatureStore
from geo_lib.contracts.envelope import ListPage
from geo_lib.tags.tag_index import TagIndex
from geo_lib.tags.tag_writer import SystemTagWriter, TagWriter


class TagService:
    @staticmethod
    def catalog(user_id: int, *, search: str = '', page: int = 1, page_size: int = 10) -> ListPage:
        return TagIndex.catalog(user_id, scope=None, search=search, page=page, page_size=page_size)

    @staticmethod
    def names(user_id: int, *, prefix: str = '') -> list[str]:
        return TagIndex.user_names(user_id, prefix=prefix, scope=None)

    @staticmethod
    def features_for_tag(user_id: int, tag_key: str, *, page: int = 1, page_size: int = 10) -> ListPage:
        return TagIndex.features_for_tag(user_id, tag_key, scope=None, page=page, page_size=page_size)

    @staticmethod
    def exists(user_id: int, tag_key: str) -> bool:
        return TagIndex.exists(user_id, tag_key, scope=None)

    @staticmethod
    def set_user_tags(feature: FeatureStore, tags) -> FeatureStore:
        return TagWriter.set_user_tags(feature, tags)

    @staticmethod
    def rename_user_tag(user, old_name: str, new_name: str) -> int:
        return TagWriter.rename_user_tag(user, old_name, new_name)

    @staticmethod
    def remove_user_tag(user, tag: str, *, feature: FeatureStore | None = None) -> int:
        return TagWriter.remove_user_tag(user, tag, feature=feature)

    @staticmethod
    def delete_features_with_tag(user, tag: str) -> int:
        return TagWriter.delete_features_with_tag(user, tag)

    @staticmethod
    def write_creation_tags(feature: FeatureStore, system_tags: list[str], extra: list[str] | None = None) -> FeatureStore:
        return SystemTagWriter.write_creation_tags(feature, system_tags, extra)
