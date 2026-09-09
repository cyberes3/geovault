from .geojson_to_kmz import KMZOptions


def prepare_kmz_options_for_share(share_name: str, base_dir: str) -> KMZOptions:
    """
    Prepare KMZOptions for share export.
    """
    return KMZOptions(
        document_name=share_name,
        embed_local_icons=True,
        icon_base_path=str(base_dir),
    )
