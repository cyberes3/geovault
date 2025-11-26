# Installation

### PostGIS

Follow these instructions: <https://trac.osgeo.org/postgis/wiki/UsersWikiPostGIS3UbuntuPGSQLApt>

```shell
apt install ca-certificates gnupg curl
curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/apt.postgresql.org.gpg >/dev/null
sudo sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list
cat << EOF >> /etc/apt/preferences.d/pgdg.pref
Package: *
Pin: release o=apt.postgresql.org
Pin-Priority: 500
EOF
sudo apt update
sudo apt install postgresql-18-postgis-3
systemctl enable --now postgresql
```

### Python 3.12

```shell
sudo apt install python3.12 python3.12-dev python3-venv
```

If your system doesn't provide Python 3.12, add this repo:

```shell
sudo add-apt-repository ppa:deadsnakes/ppa
```

### Clone Repository

```shell
adduser --system geovault --home /srv/geovault
```

```shell
cd /srv/geovault
git clone https://git.evulid.cc/cyberes/geovault.git
```

```shell
cd geovault/srv/backend
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
```

### Config Setup

```shell
cp config.example.yaml config.yaml
```

Then fill in your values in the config.

### Systemd Setup

```shell
chown -R geovault:nogroup /srv/geovault/
```

