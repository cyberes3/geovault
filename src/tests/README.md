# gv_tests Test Suite

```shell
./run_tests.sh
```

You need to create the PostGIS extension in the test DB first.

```sql
\c gv_tests
ALTER DATABASE gv_tests OWNER TO gv_tests;
GRANT CREATE ON SCHEMA public TO gv_tests;
ALTER SCHEMA public OWNER TO gv_tests;

CREATE EXTENSION IF NOT EXISTS postgis;
GRANT ALL PRIVILEGES ON TABLE geometry_columns TO gv_tests;
GRANT ALL PRIVILEGES ON TABLE geography_columns TO gv_tests;
GRANT ALL PRIVILEGES ON TABLE spatial_ref_sys TO gv_tests;
ALTER SCHEMA public OWNER TO gv_tests;
GRANT ALL ON SCHEMA public TO gv_tests;
GRANT USAGE ON SCHEMA public TO PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO gv_tests;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO gv_tests;
```