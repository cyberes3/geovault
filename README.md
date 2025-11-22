# GeoVault

*Platform to organize your personal spatial data in a unified database.*

An outdoorsman tends to collect all sorts of spatial data: tracks of hikes, points of interest, and so on. This data
tends to be scattered across numerous files stored in your documents and it isn't easy to see where you've been.
*GeoVault* is a web platform that stores this data and presents *all* of it on one map.

Development is done on my personal Git server, [git.evulid.cc](https://git.evulid.cc/cyberes/geovault), and is mirrored
to [GitHub](https://github.com/Cyberes/geovault).

**Features:**

- Streamlined upload and import process that makes it easy to shove your spatial data into the database
- KMZ, KML, and GPX files supported
- Tag and collection based organization
- Link-based public sharing
- Reverse geocoding to show what features are associated with

**This platform does not support editing.** Use your own preferred tool and then upload your data to the server.

https://trac.osgeo.org/postgis/wiki/UsersWikiPostGIS3UbuntuPGSQLApt

sudo add-apt-repository ppa:deadsnakes/ppa
sudo apt install python3.12 python3.12-dev

python manage.py createsuperuser