-- Flex config: import only rivers and canals (waterway=river, waterway=canal) as linestrings.
-- Import with: osm2pgsql -x -O flex -S waterways.lua --schema waterways -s -d $DB file.osm.pbf
-- Geometry in EPSG:4326.

local waterways = osm2pgsql.define_way_table('waterways', {
    { column = 'osm_id', type = 'int8', not_null = true },
    { column = 'waterway', type = 'text', not_null = true },
    { column = 'name', type = 'text' },
    { column = 'tags', type = 'jsonb' },
    { column = 'geom', type = 'linestring', not_null = true, projection = 4326 },
    { column = 'created', sql_type = 'timestamptz' },
}, {
    indexes = {
        { column = 'geom', method = 'gist' },
        { column = 'waterway', method = 'btree' },
    },
})

local function format_timestamp(ts)
    if ts == nil then return nil end
    return os.date('!%Y-%m-%dT%H:%M:%SZ', ts)
end

function osm2pgsql.process_way(object)
    local w = object.tags and object.tags.waterway
    if w ~= 'river' and w ~= 'canal' then
        return
    end
    local geom = object:as_linestring()
    if not geom then
        return
    end
    waterways:insert({
        osm_id = object.id,
        waterway = w,
        name = object.tags.name,
        tags = object.tags,
        geom = geom,
        created = format_timestamp(object.timestamp),
    })
end
