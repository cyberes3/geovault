# Duplicate Detection

GeoVault uses two different methods to detect duplicate features, each serving a different purpose. Understanding how these work will help you manage your spatial data more effectively.

## Hash-Based Duplicate Detection

**What it detects:** Features that are **completely identical** in both location and properties.

Hash-based detection compares the entire feature, including:
- Geometry (coordinates)
- Name
- Description
- Tags
- Styling (colors, icons)
- All other properties

**When it's used:**
- When importing features from the import queue to your feature library
- When checking for duplicates across different items in your import queue

**What happens:**
- If a feature with the exact same hash already exists in your library, it will be automatically skipped during import
- If a feature in one import queue item has the same hash as a feature in another unimported item, it will be skipped and you'll see a warning with a link to the original item

**Example:**
If you upload a file with a point named "Summit Peak" at coordinates (-122.5, 37.8) with a red icon, and later upload another file with the exact same point (same name, same coordinates, same icon), the second one will be detected as a hash-based duplicate and skipped.

## Coordinate-Based Duplicate Detection

**What it detects:** Features that share the **same location** but may have different names, descriptions, or other properties.

Coordinate-based detection only compares the geometry (coordinates), ignoring all properties like name, description, tags, and styling.

**When it's used:**
- During file processing, before features are added to the import queue
- When you manually recheck duplicates for an import queue item

**What happens:**
- Features with matching coordinates are flagged as potential duplicates
- You'll see a warning on the import page indicating that a feature shares coordinates with an existing feature in your library
- These features will be automatically skipped during import to prevent creating duplicate features at the same location
- A link is provided to view the existing feature on the map

**Example:**
If you have a point named "Gas Station" at coordinates (-122.5, 37.8) in your library, and you upload a file with a point named "Coffee Shop" at the same coordinates, coordinate-based detection will flag it as a duplicate. Even though the names are different, the feature will be automatically skipped during import to prevent having multiple features at the exact same location.

## Why Two Methods?

The two detection methods serve different purposes:

- **Hash-based detection** prevents importing exact copies of features you already have, keeping your library clean and avoiding true duplicates
- **Coordinate-based detection** prevents importing features at the same location as existing features, even if they have different names or properties. This helps maintain data quality by avoiding duplicate locations

## Cross-Queue Duplicate Detection

When you have multiple files in your import queue, GeoVault also checks for duplicates between items that haven't been imported yet. If a feature in one queue item is identical (hash-based) to a feature in another queue item, the newer item's duplicate will be skipped during import, and you'll see a warning with a link to navigate to the original item in the queue.

This helps prevent importing the same feature multiple times when you're processing several files at once.

## Best Practices

1. **Coordinate duplicates are blocked:** Features flagged as coordinate duplicates will be automatically skipped during import. If you need to update an existing feature at that location, edit the existing feature instead of trying to import a new one

2. **Trust hash-based detection:** If a feature is flagged as a hash-based duplicate, it's an exact copy and will be automatically skipped. This is usually what you want

3. **Use the links:** Both detection methods provide links to view the existing features. Use these to compare and decide whether to proceed with importing

4. **Recheck if needed:** If you've imported new features after uploading a file, you can use the "Recheck Duplicates" button to update the duplicate detection for items still in your import queue

