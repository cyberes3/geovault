# MaxMind GeoIP2 Database Setup for Ubuntu

The GeoServer application uses MaxMind's GeoIP2 database to determine user location based on their IP address. This allows the map to automatically center on the user's location instead of using a hardcoded default.



## Installation Steps

### 1. Get MaxMind License Key

1. **Create a free MaxMind account:**
   - Visit: https://www.maxmind.com/en/geolite2/signup
   - Sign up for a free account
2. **Generate a license key:**
   - Log into your MaxMind account
   - Go to "My License Key" section
   - Generate a new license key



### 2. Install MaxMind GeoIP2 Database

1. **Install the geoipupdate package:**
   ```bash
   sudo apt update
   sudo apt install geoipupdate
   ```

2. **Configure geoipupdate:**
   ```bash
   # Edit the configuration file
   sudo nano /etc/GeoIP.conf
   ```

3. **Add your MaxMind license key to the config:**
   ```ini
   # The following UserId and LicenseKey are required placeholders and
   # do not work as actual license keys.  You must sign up for a free
   # MaxMind account in order to get a working license key.  You can
   # do so at https://www.maxmind.com/en/geolite2/signup.
   UserId YOUR_USER_ID
   LicenseKey YOUR_LICENSE_KEY
   
   # GeoLite2 databases to download
   EditionIDs GeoLite2-City
   ```

4. **Download the database:**
   ```bash
   sudo geoipupdate
   ```



## Automatic Updates

   ```bash
   sudo systemctl enable geoipupdate.timer
   sudo systemctl start geoipupdate.timer
   ```
