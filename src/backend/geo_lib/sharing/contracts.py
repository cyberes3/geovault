from typing import Annotated, Literal, Optional, Union
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, RootModel, TypeAdapter, model_validator

from geo_lib.sharing.constants import (
    AUDIENCE_AUTHENTICATED,
    AUDIENCE_WORLD,
    KIND_COLLECTION,
    KIND_FEATURE,
    KIND_LIVE_TRACK,
    KIND_LIVE_TRACK_GROUP,
    KIND_TAG,
)

ShareType = Literal["tag", "collection", "feature", "live_track", "live_track_group"]
MapAudience = Literal["world"]
ShareAudience = Literal["world", "authenticated"]


class _ShareModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class TagShareCreate(_ShareModel):
    share_type: Literal["tag"] = KIND_TAG
    tag: str = Field(min_length=1)
    include_tags: bool = False
    allow_downloads: bool = False

    @model_validator(mode="after")
    def strip_tag(self) -> "TagShareCreate":
        self.tag = self.tag.strip()
        if not self.tag:
            raise ValueError("tag is required when share_type is \"tag\"")
        return self


class CollectionShareCreate(_ShareModel):
    share_type: Literal["collection"] = KIND_COLLECTION
    collection_id: UUID
    include_tags: bool = False
    allow_downloads: bool = False


class FeatureShareCreate(_ShareModel):
    share_type: Literal["feature"] = KIND_FEATURE
    feature_id: int = Field(gt=0)
    include_tags: bool = False
    allow_downloads: bool = False


class LiveTrackShareCreate(_ShareModel):
    share_type: Literal["live_track"] = KIND_LIVE_TRACK
    track_id: UUID
    audience: ShareAudience
    include_tags: bool = False
    allow_downloads: bool = False


class LiveTrackGroupShareCreate(_ShareModel):
    share_type: Literal["live_track_group"] = KIND_LIVE_TRACK_GROUP
    group_id: UUID
    audience: ShareAudience
    include_tags: bool = False
    allow_downloads: bool = False


CreateSharePayload = Annotated[
    Union[
        TagShareCreate,
        CollectionShareCreate,
        FeatureShareCreate,
        LiveTrackShareCreate,
        LiveTrackGroupShareCreate,
    ],
    Field(discriminator="share_type"),
]

CreateShareAdapter = TypeAdapter(CreateSharePayload)


class CreateSharePayloadModel(RootModel[CreateSharePayload]):
    """Discriminated create body. model_dump() is the inner variant."""

    def model_dump(self, **kwargs):
        return self.root.model_dump(**kwargs)


class UpdateSharePayload(_ShareModel):
    allow_downloads: Optional[bool] = None
    include_tags: Optional[bool] = None

    @model_validator(mode="after")
    def require_one_field(self) -> "UpdateSharePayload":
        if self.allow_downloads is None and self.include_tags is None:
            raise ValueError("allow_downloads or include_tags is required")
        return self


class TagShareListItem(_ShareModel):
    share_type: Literal["tag"] = KIND_TAG
    share_id: str
    url: str
    tag: str
    created_at: str
    access_count: int
    include_tags: bool
    allow_downloads: bool
    audience: MapAudience = AUDIENCE_WORLD
    domain: Literal["map"] = "map"


class CollectionShareListItem(_ShareModel):
    share_type: Literal["collection"] = KIND_COLLECTION
    share_id: str
    url: str
    collection_id: str
    collection_name: str
    created_at: str
    access_count: int
    include_tags: bool
    allow_downloads: bool
    audience: MapAudience = AUDIENCE_WORLD
    domain: Literal["map"] = "map"


class FeatureShareListItem(_ShareModel):
    share_type: Literal["feature"] = KIND_FEATURE
    share_id: str
    url: str
    feature_id: int
    feature_name: str
    created_at: str
    access_count: int
    include_tags: bool
    allow_downloads: bool
    audience: MapAudience = AUDIENCE_WORLD
    domain: Literal["map"] = "map"


class LiveTrackShareListItem(_ShareModel):
    share_type: Literal["live_track"] = KIND_LIVE_TRACK
    share_id: str
    url: str
    track_id: str
    track_name: str
    created_at: str
    access_count: int
    include_tags: bool
    allow_downloads: bool
    audience: ShareAudience
    domain: Literal["live_track"] = "live_track"


class LiveTrackGroupShareListItem(_ShareModel):
    share_type: Literal["live_track_group"] = KIND_LIVE_TRACK_GROUP
    share_id: str
    url: str
    group_id: str
    group_name: str
    created_at: str
    access_count: int
    include_tags: bool
    allow_downloads: bool
    audience: ShareAudience
    domain: Literal["live_track"] = "live_track"


ShareListItem = Annotated[
    Union[
        TagShareListItem,
        CollectionShareListItem,
        FeatureShareListItem,
        LiveTrackShareListItem,
        LiveTrackGroupShareListItem,
    ],
    Field(discriminator="share_type"),
]


class PublicTagShare(_ShareModel):
    share_type: Literal["tag"] = KIND_TAG
    tag: str
    created_at: str
    include_tags: bool
    allow_downloads: bool


class PublicCollectionShare(_ShareModel):
    share_type: Literal["collection"] = KIND_COLLECTION
    collection_name: str
    collection_id: str
    created_at: str
    include_tags: bool
    allow_downloads: bool


class PublicFeatureShare(_ShareModel):
    share_type: Literal["feature"] = KIND_FEATURE
    feature_name: str
    created_at: str
    include_tags: bool
    allow_downloads: bool


class PublicLiveTrackShare(_ShareModel):
    share_type: Literal["live_track"] = KIND_LIVE_TRACK
    share_access: Literal["world", "internal"]
    track_id: str
    track_name: str
    created_at: str


class PublicLiveTrackGroupShare(_ShareModel):
    share_type: Literal["live_track_group"] = KIND_LIVE_TRACK_GROUP
    share_access: Literal["world", "internal"]
    group_id: str
    group_name: str
    created_at: str


PublicShare = Annotated[
    Union[
        PublicTagShare,
        PublicCollectionShare,
        PublicFeatureShare,
        PublicLiveTrackShare,
        PublicLiveTrackGroupShare,
    ],
    Field(discriminator="share_type"),
]
