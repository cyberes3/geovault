# GeoVault Test Suite

When doing AI programming its really really nice to have comprehensive tests.

Must do `pip install Flask==3.1.3`

## Running Tests

### Run all tests
```shell
./run-tests.sh
```

### Run all tests with verbose output
```shell
./run-tests.sh -v
```

`-v` is the only supported arg. Pytest args are not supported. 

### Run a specific test folder
```shell
# Run all API tests
./run-tests.sh test_api

# Run all validation tests
./run-tests.sh test_validation

# Run all processing tests
./run-tests.sh test_processing
```

### Run a specific test file
```shell
# Run the E2E import tests
./run-tests.sh test_api/test_e2e_import.py

# Run the features tests
./run-tests.sh test_api/test_features.py

# Run with verbose output
./run-tests.sh test_api/test_e2e_import.py -v
```

### Run a specific test class or test function
```shell
# Run a specific test class
./run-tests.sh test_api/test_e2e_import.py::TestE2EImport

# Run a specific test function
./run-tests.sh test_api/test_e2e_import.py::TestE2EImport::test_e2e_kml_import

# Run multiple specific tests
./run-tests.sh test_api/test_e2e_import.py::TestE2EImport::test_e2e_kml_import test_api/test_e2e_import.py::TestE2EImport::test_e2e_gpx_import
```

### Run tests matching a keyword
```shell
# Run all tests with "duplicate" in their name
./run-tests.sh -k duplicate

# Run all tests with "icon" in their name
./run-tests.sh -k icon
```

## Test Organization

The test suite is organized into the following directories:

- **test_api/** - API endpoint tests (features, collections, import, sharing, etc.)
- **test_auth/** - Authentication tests (API keys, session auth)
- **test_concurrent/** - Concurrent operation tests
- **test_edge_cases/** - Edge case and boundary condition tests
- **test_error_recovery/** - Error recovery and retry mechanism tests
- **test_geo_lib/** - Geospatial library tests
- **test_models/** - Django model tests
- **test_performance/** - Performance and benchmarking tests
- **test_processing/** - File processing and bulk operation tests
- **test_security/** - Security and middleware tests
- **test_utils/** - Test helper utilities
- **test_validation/** - Data validation tests (GeoJSON, geometry, styling)

## Notable Test Files

- **test_api/test_e2e_import.py** - Comprehensive end-to-end import flow tests (KML, KMZ, GPX)
- **test_api/test_features.py** - Feature CRUD operations and querying
- **test_api/test_collections.py** - Collection management tests
- **test_api/test_tag_autocomplete.py** - Tag autocomplete functionality tests (user/system tag separation, search)
- **test_concurrent/test_concurrent_operations.py** - Race condition and concurrent access tests
- **test_processing/test_processors.py** - File format processor tests

## Optional test environment (.env)

Some tests need external config (e.g. areas server DB for waterways checks). Copy `tests/.env.example` to `tests/.env` and set variables as needed. `tests/.env` is gitignored. If not set, those tests are skipped.

## Database Setup

Before running tests for the first time, you need to create the PostGIS extension in the test database.

```sql
\c gv_tests
ALTER DATABASE gv_tests OWNER TO gv_tests;
GRANT CREATE ON SCHEMA public TO gv_tests;
ALTER SCHEMA public OWNER TO gv_tests;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO gv_tests;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO gv_tests;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO gv_tests;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO gv_tests;

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
