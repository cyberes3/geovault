"""
OpenTopoMap tile source configuration.

OpenTopoMap was founded in 2011 by Stefan and Philipp as an experiment. The goal was to 
imitate the official topographic map style of German Vermessungsämter with free software 
and OpenStreetMap data. At that time, the "slippy map" on openstreetmap.org looked kind 
of childish and the project always emphasized that it was a database, not a map. I spent 
an incredible amount of time into optimizing the map style level by level, starting each 
level again on a white plain and experimentally adding map features by their tagging and 
manually tweaking each color, line width, casing width, pattern, symbol etc. until I was 
satisfied. While studying at University and still without family obligations, time was 
available. At that time, the University of Erlangen thankfully supported us by financing 
a local server. The number of users increased month after month. Currently, OpenTopoMap 
has more than 2 million distinct users per month and OpenTopoMap is highly ranked and 
well-known. It was never my goal to make any profit, at the cost that I never gave any 
service or support guarantee. After being employed as research assistants for many years 
(not in cartography or similar...), the connection to the University faded away and we 
now need to retire and return the machine.

What's next?

The main server will be shut down soon. It contains the massive postgresql database and 
Mapnik as a renderer. I backed up all previously rendered tiles between zoom level 0 and 
13 to an external, smaller server, which will continue to serve those pre-rendered tiles. 
There are no plans to set up a new database and Mapnik instance again, as they still 
require a powerful hardware and - more limiting - a lot of time and effort. Please be 
prepared to lose access to all png tiles!

Outlook

This won't be the end. I have done experiments with vector maps and a very lightweight 
software stack (tilemaker and MapLibreGLJS). Vector maps are technically more up-to-date 
and are more interactive than dumb png tiles. However, they require a completely different 
software stack at the clients, it is not done with a plain image viewer anymore. The 
computational cost of rendering is shifted to the clients. For OpenTopoMap, I don't have a 
schedule, development is solely done in my spare spare time :-).

Potential replacement: https://github.com/sletuffe/openmaps.fr/discussions/1

This tile source does not require a proxy as it can be accessed directly.
Note: Only pre-rendered tiles for zoom levels 0-13 are available after the main server shutdown.
"""

from geo_lib.tile_sources.registry import TileSource


class OpenTopoMapTileSource(TileSource):
    """OpenTopoMap tile source."""
    
    @property
    def id(self):
        return 'opentopomap'
    
    @property
    def name(self):
        return 'OpenTopoMap'
    
    @property
    def type(self):
        return 'xyz'
    
    @property
    def url_template(self):
        return 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png'
    
    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
            'tileSubdomains': ['a', 'b', 'c']
        }
