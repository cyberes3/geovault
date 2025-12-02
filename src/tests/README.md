# GeoVault Test Suite

When doing AI programming its really really nice to have comprehensive tests.

## Running Tests

### Run all tests
```shell
./run_tests.sh
```

### Run all tests with verbose output
```shell
./run_tests.sh -v
```

### Run a specific test folder
```shell
# Run all API tests
./run_tests.sh test_api

# Run all validation tests
./run_tests.sh test_validation

# Run all processing tests
./run_tests.sh test_processing
```

### Run a specific test file
```shell
# Run the E2E import tests
./run_tests.sh test_api/test_e2e_import.py

# Run the features tests
./run_tests.sh test_api/test_features.py

# Run with verbose output
./run_tests.sh test_api/test_e2e_import.py -v
```

### Run a specific test class or test function
```shell
# Run a specific test class
./run_tests.sh test_api/test_e2e_import.py::TestE2EImport

# Run a specific test function
./run_tests.sh test_api/test_e2e_import.py::TestE2EImport::test_e2e_kml_import

# Run multiple specific tests
./run_tests.sh test_api/test_e2e_import.py::TestE2EImport::test_e2e_kml_import test_api/test_e2e_import.py::TestE2EImport::test_e2e_gpx_import
```

### Run tests matching a keyword
```shell
# Run all tests with "duplicate" in their name
./run_tests.sh -k duplicate

# Run all tests with "icon" in their name
./run_tests.sh -k icon
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

## Tag Autocomplete Tests

The **test_api/test_tag_autocomplete.py** file contains comprehensive tests for the tag autocomplete functionality via the `/api/features/by-tag/` endpoint. These tests prevent regressions of critical bugs:

1. **System tags appearing in user tag autocomplete** - System tags like `driving:yes`, `type:point`, `import-year:2025`, etc. should never appear in user tag suggestions
2. **Tag availability** - All user tags should be available in the autocomplete list

### Test Classes

- **TestTagSeparation** - Verifies user tags and system tags are properly separated in API responses
  - Tests that system tag prefixes (type, import-year, elevation, driving, etc.) are correctly identified
  - Tests multiple features with mixed tags are aggregated correctly
  - Tests empty tags are filtered out

- **TestAllTagsReturned** - Verifies the API returns all tags (no pagination)
  - Tests with few tags (all returned)
  - Tests with many tags (all returned)
  - Tests that both user and system tags are fully returned and properly separated

- **TestTagSearch** - Tests tag search/filtering functionality
  - Tests search filters both user and system tags
  - Tests search is case-insensitive
  - Tests search maintains proper separation

- **TestTagAutocompleteIntegration** - Integration tests simulating real-world scenarios
  - Tests importing multiple GPX files with tags
  - Tests the specific bug scenario from the user report (driving:yes appearing in user tags)

### Running Tag Autocomplete Tests

```shell
# Run all tag autocomplete tests
./run_tests.sh test_api/test_tag_autocomplete.py

# Run specific test class
./run_tests.sh test_api/test_tag_autocomplete.py::TestTagSeparation

# Run specific test
./run_tests.sh test_api/test_tag_autocomplete.py::TestTagAutocompleteIntegration::test_editing_feature_shows_only_user_tags_in_autocomplete
```

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
