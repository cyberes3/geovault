# Reverse Geocoding

If you want advanced tagging of your features you will have to set up a very heavy service. This feature is disabled by default via the `geocoding.enabled` config value.


[Overpass API](https://github.com/wiktorn/Overpass-API) is used to find all other features.



Docker Compose files are provided to help you get the services running with minimal pain. Running these two services requires a minimum of 25GB RAM, 6 CPU cores, 500GB, and SSDs. This could literally take days to import the data up, so be patient.



### Setup

1. Install Docker
2. `mkdir -p /srv/docker-data/nominatim/db /srv/docker-data/nominatim/flatnode /srv/docker-data/overpass`
3. `docker compose -f nominatim.yml up`
4. `./download-overpass-data.sh`
5. `docker compose -f overpass.yml up`
6. Wait 2 days and come back



This will run the containers in the foreground so you can monitor their progress. `CTRL+C` the containers when they have
finished building their databases and then start them normally:

```shell
docker compose -f nominatim.yml up -d
docker compose -f overpass.yml up -d
```

