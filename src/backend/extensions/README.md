# Geovault Extensions

This directory contains modular extensions for the Geovault platform.

## Quick Start

Each extension should follow this structure:

```text
extensions/
└── <extension_name>/
    ├── manifest.py
    └── src/
        └── backend/
            ├── __init__.py
            ├── models.py
            └── ...
```

### manifest.py
Required fields:
```python
name = "my_extension"
version = "1.0.0"
```

### Database & Tables
Extensions are standard Django apps. Tables will be automatically prefixed with your extension name (e.g., `my_extension_mytable`).

Run migrations from the root:
```bash
python manage.py makemigrations
python manage.py migrate
```

## Configuration
Extensions can be enabled/disabled or configured in `src/backend/config.yaml`:

```yaml
extensions:
  my_extension:
    enabled: true
```
