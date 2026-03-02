-- Flex config for is_in area server: admin boundaries and protected areas.
-- Import with: osm2pgsql -x -O flex -S areas.lua --schema is_in -s -d $DB planet.pbf
-- (-x = extra-attributes for object.timestamp). Geometry in EPSG:4326.

local ADMIN_LEVELS = { ['2'] = true, ['4'] = true, ['6'] = true, ['8'] = true }

local function format_timestamp(ts)
    if ts == nil then return nil end
    return os.date('!%Y-%m-%dT%H:%M:%SZ', ts)
end

local function get_name(tags)
    return tags.name or tags['name:en'] or tags['int_name']
end

local function is_protected_area_tag(tags)
    local boundary = tags.boundary or ''
    local leisure = tags.leisure or ''
    local landuse = tags.landuse or ''
    if boundary == 'protected_area' or boundary == 'national_park' then
        return true
    end
    if leisure == 'nature_reserve' or leisure == 'park' then
        return true
    end
    if landuse == 'recreation_ground' then
        return true
    end
    return false
end

-- Flowing/linear water types we exclude (not standing lakes): river, stream, canal, ditch, drain, rapids.
local WATER_EXCLUDE = {
    ['river'] = true, ['stream'] = true, ['canal'] = true,
    ['ditch'] = true, ['drain'] = true, ['rapids'] = true,
}

-- Water bodies: name required. Include natural=water (and water=* except excluded), natural=lake, landuse=reservoir.
-- Exclude water=river/stream/canal/ditch/drain/rapids so we keep standing water (lakes, ponds, reservoirs, lagoons, etc.).
-- Exclude only man-made swimming pools (leisure=swimming_pool, amenity=swimming_pool). Do not exclude
-- sport=swimming or leisure=swimming_area: those can apply to natural lakes (designated swimming zones).
local function is_water_tag(tags)
    if not get_name(tags) then return false end
    if tags.leisure == 'swimming_pool' or tags.amenity == 'swimming_pool' then
        return false
    end
    local natural = tags.natural or ''
    local water = tags.water or ''
    local landuse = tags.landuse or ''
    if WATER_EXCLUDE[water] then return false end
    if natural == 'water' then return true end
    if natural == 'lake' then return true end  -- deprecated, prefer natural=water + water=lake
    if landuse == 'reservoir' then return true end  -- deprecated, prefer natural=water + water=reservoir
    if water == 'lake' or water == 'reservoir' or water == 'pond' then return true end
    if water == 'lagoon' or water == 'oxbow' or water == 'basin' or water == 'cenote' then return true end
    return false
end

local function water_type_from_tags(tags)
    local w = tags and tags.water or ''
    if w == 'lake' or w == 'reservoir' or w == 'pond' then return w end
    if w == 'lagoon' or w == 'oxbow' or w == 'basin' or w == 'cenote' then return w end
    if tags and tags.landuse == 'reservoir' then return 'reservoir' end
    return 'water'
end

-- Admin areas: administrative boundaries levels 2,4,6,8 (country, state, county, city).
-- One row per polygon part; geom in 4326 for ST_Contains.
local admin_areas = osm2pgsql.define_relation_table('admin_areas', {
    { column = 'osm_id', type = 'int8', not_null = true },
    { column = 'admin_level', type = 'int2', not_null = true },
    { column = 'name', type = 'text' },
    { column = 'tags', type = 'jsonb' },
    { column = 'geom', type = 'polygon', not_null = true, projection = 4326 },
    { column = 'created', sql_type = 'timestamptz' },
}, { indexes = {
    { column = 'geom', method = 'gist' },
    { column = 'admin_level', method = 'btree' },
}})

-- Protected areas: parks, nature reserves, protected_area, national_park, recreation_ground.
-- Area table so we get both closed ways and multipolygon relations.
local protected_areas = osm2pgsql.define_area_table('protected_areas', {
    { column = 'osm_id', type = 'int8', not_null = true },
    { column = 'name', type = 'text' },
    { column = 'tags', type = 'jsonb' },
    { column = 'geom', type = 'geometry', not_null = true, projection = 4326 },
    { column = 'created', sql_type = 'timestamptz' },
}, { indexes = {
    { column = 'geom', method = 'gist' },
}})

-- Water bodies: lakes, reservoirs, ponds (polygon for shoreline + on-water).
-- tags stored so delete-small-lakes.py can also remove swimming pools etc. by tag.
local water_bodies = osm2pgsql.define_area_table('water_bodies', {
    { column = 'osm_id', type = 'int8', not_null = true },
    { column = 'name', type = 'text' },
    { column = 'water_type', type = 'text' },
    { column = 'tags', type = 'jsonb' },
    { column = 'geom', type = 'geometry', not_null = true, projection = 4326 },
    { column = 'created', sql_type = 'timestamptz' },
}, { indexes = {
    { column = 'geom', method = 'gist' },
}})

-- Place nodes: OSM nodes with place=city|town|village for "nearest city" when admin has no city.
local PLACE_TYPES = { ['city'] = true, ['town'] = true, ['village'] = true }
local place_nodes = osm2pgsql.define_node_table('place_nodes', {
    { column = 'osm_id', type = 'int8', not_null = true },
    { column = 'name', type = 'text' },
    { column = 'place_type', type = 'text' },
    { column = 'geom', type = 'point', not_null = true, projection = 4326 },
    { column = 'created', sql_type = 'timestamptz' },
}, { indexes = {
    { column = 'geom', method = 'gist' },
}})

function osm2pgsql.process_relation(object)
    local t = object.tags

    -- Administrative boundaries for admin levels 2, 4, 6, 8
    if t.boundary == 'administrative' and ADMIN_LEVELS[t.admin_level] then
        local admin_level = tonumber(t.admin_level)
        if not admin_level then return end
        local name = get_name(t)
        local created = format_timestamp(object.timestamp)
        for geom in object:as_multipolygon():geometries() do
            admin_areas:insert({
                osm_id = object.id,
                admin_level = admin_level,
                name = name,
                tags = t,
                geom = geom,
                created = created,
            })
        end
        return
    end

    -- Protected areas (multipolygon or boundary relations)
    if (t.type == 'multipolygon' or t.type == 'boundary') and is_protected_area_tag(t) then
        local geom = object:as_multipolygon()
        if geom then
            protected_areas:insert({
                osm_id = object.id,
                name = get_name(t),
                tags = t,
                geom = geom,
                created = format_timestamp(object.timestamp),
            })
        end
        return
    end

    -- Water bodies (multipolygon relations)
    if (t.type == 'multipolygon' or t.type == 'boundary') and is_water_tag(t) then
        local geom = object:as_multipolygon()
        if geom then
            water_bodies:insert({
                osm_id = object.id,
                name = get_name(t),
                water_type = water_type_from_tags(t),
                tags = t,
                geom = geom,
                created = format_timestamp(object.timestamp),
            })
        end
    end
end

function osm2pgsql.process_way(object)
    if not object.is_closed then return end

    if is_protected_area_tag(object.tags) then
        local geom = object:as_polygon()
        if geom then
            protected_areas:insert({
                osm_id = object.id,
                name = get_name(object.tags),
                tags = object.tags,
                geom = geom,
                created = format_timestamp(object.timestamp),
            })
        end
        return
    end

    if is_water_tag(object.tags) then
        local geom = object:as_polygon()
        if geom then
            water_bodies:insert({
                osm_id = object.id,
                name = get_name(object.tags),
                water_type = water_type_from_tags(object.tags),
                tags = object.tags,
                geom = geom,
                created = format_timestamp(object.timestamp),
            })
        end
    end
end

function osm2pgsql.process_node(object)
    local t = object.tags
    if not t or not PLACE_TYPES[t.place] then return end
    local name = get_name(t)
    if not name or not name:match('%S') then return end
    place_nodes:insert({
        osm_id = object.id,
        name = name,
        place_type = t.place,
        geom = object:as_point(),
        created = format_timestamp(object.timestamp),
    })
end
