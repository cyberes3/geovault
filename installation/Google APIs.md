# Google APIs

### Geocoding

<https://developers.google.com/maps/documentation/geocoding/overview>

1. Go to <https://console.cloud.google.com>
2. Create a project and set up billing
3. Enable the Geocoding API
   at <https://console.cloud.google.com/marketplace/product/google/geocoding-backend.googleapis.com>

The base URL for the Google Maps API console is <https://console.cloud.google.com/google/maps-apis>.

After setting up your project in Google Cloud, go to <https://console.cloud.google.com/google/maps-apis/credentials> and
generate a new API key. Enter it into your `config.yaml`.

Learn how to set quotas at <https://developers.google.com/maps/documentation/geocoding/usage-and-billing>
and <https://developers.google.com/maps/billing-and-pricing/pricing#places-pricing>. First 10,000 requests per day are
free.