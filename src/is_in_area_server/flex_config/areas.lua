-- Flex config for is_in area server: admin boundaries and protected areas.
-- Import with: osm2pgsql -O flex -S areas.lua --schema is_in -s -d $DB planet.pbf
-- Geometry in EPSG:4326 for point-in-polygon with ST_Contains(geom, point).

local ADMIN_LEVELS = { ['2'] = true, ['4'] = true, ['6'] = true, ['8'] = true }

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

-- Admin areas: administrative boundaries levels 2,4,6,8 (country, state, county, city).
-- One row per polygon part; geom in 4326 for ST_Contains.
local admin_areas = osm2pgsql.define_relation_table('admin_areas', {
    { column = 'osm_id', type = 'int8', not_null = true },
    { column = 'admin_level', type = 'int2', not_null = true },
    { column = 'name', type = 'text' },
    { column = 'tags', type = 'jsonb' },
    { column = 'geom', type = 'polygon', not_null = true, projection = 4326 },
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
        for geom in object:as_multipolygon():geometries() do
            admin_areas:insert({
                osm_id = object.id,
                admin_level = admin_level,
                name = name,
                tags = t,
                geom = geom,
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
            })
        end
    end
end

function osm2pgsql.process_way(object)
    if not object.is_closed then return end
    if not is_protected_area_tag(object.tags) then return end

    local geom = object:as_polygon()
    if not geom then return end

    protected_areas:insert({
        osm_id = object.id,
        name = get_name(object.tags),
        tags = object.tags,
        geom = geom,
    })
end
