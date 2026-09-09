from geo_lib.track.store import PointStore


def seed_track_points(track, coordinates, params=None):
    if params is None:
        params = [{} for _ in coordinates]
    PointStore(coordinates, params).persist_to_track(track)


def stored_track_points(track):
    store = PointStore.from_track(track)
    return store.coordinates, store.params
