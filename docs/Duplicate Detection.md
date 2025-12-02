# Duplicate Detection

GeoVault automatically checks your uploaded files for duplicate features to keep your map organized and prevent confusion. When you upload a file, GeoVault compares each feature against what you already have in your library and what's waiting in your import queue.

## Understanding Duplicate Types

GeoVault uses **two methods** to identify duplicates, checking in **two places**. This gives you four possible types of duplicates:

### The Two Methods

1. **Exact Match (Hash-Based)**
   - The feature is **completely identical** to one you already have
   - Compares everything: location, name, description, tags, colors, icons—all properties
   - Think of it like a fingerprint: if everything matches, it's the exact same feature
   - **These are always blocked** because importing them would create an exact copy

2. **Same Location (Geometry-Based)**
   - The feature is at the **exact same spot** as another feature, but has different properties
   - Only compares the coordinates, ignoring names, descriptions, and other details
   - Example: You have a point called "Gas Station" and upload a point called "Coffee Shop" at the same coordinates
   - **These are also blocked by default** to prevent cluttering the same location with multiple features

### The Two Places We Check

1. **Your Feature Library**
   - Features you've already imported and are visible on your map
   - These are permanently saved to your account

2. **Your Import Queue**
   - Files you've uploaded but haven't imported yet
   - Only checks files that were uploaded **before** the current one
   - This prevents importing the same feature multiple times when processing several files

## What You'll See

When GeoVault finds duplicates, you'll see clear warnings organized by type:

### 🔴 Exact Duplicates - Blocked

**"Exact Duplicate in Feature Library (Blocked)"**
- This exact feature is already on your map
- It will be automatically skipped—you can't import it
- A link shows you where it is on your map

**"Exact Duplicate in Import Queue (Blocked)"**
- This exact feature is in another file you uploaded earlier
- It will be automatically skipped—you can't import it
- A link shows you which file contains the original

### 🟡 Same Location - Skipped

**"Same Location as Feature in Library"**
- A different feature already exists at this exact spot on your map
- It's automatically skipped to avoid confusion
- A link shows you the existing feature
- **Note:** You can't restore these—if you need to update the location, edit the existing feature instead

**"Same Location as Feature in Import Queue"**
- A feature at this exact spot is in another file you uploaded earlier
- It's automatically skipped to avoid confusion
- A link shows you which file contains the other feature
- **Note:** You can't restore these either

## How Duplicate Detection Works

When you upload a file, GeoVault follows these steps:

1. **Remove Internal Duplicates**: If the same feature appears multiple times in your file, GeoVault keeps only one copy

2. **Check Your Library First**:
   - Looks for exact matches → blocks them
   - Looks for same-location matches → blocks them

3. **Check Import Queue**:
   - For any features that passed step 2, check if they match features in older queue items
   - Looks for exact matches → blocks them
   - Looks for same-location matches → blocks them

4. **Ready to Import**:
   - Any features that aren't duplicates are ready to be imported

## Why Block Same-Location Features?

You might wonder why GeoVault blocks features at the same location, even if they have different names. Here's why:

- **Visual Clarity**: Multiple features at the exact same spot create confusion on the map
- **Data Quality**: Usually indicates a data error or unintentional duplicate
- **Intentional Updates**: If you need to change a feature's name or properties, edit the existing one rather than creating a new one

## Example Scenarios

### Scenario 1: Re-uploading the Same File
You upload "trails.geojson" on Monday and import it. On Tuesday, you accidentally upload the same file again.

**Result**: All features will show as "Exact Duplicate in Feature Library (Blocked)" because they're already on your map.

### Scenario 2: Updated Data with Same Locations
You have 50 trail markers on your map. You get an updated file with the same 50 trails but with updated descriptions.

**Result**: All 50 features will show as "Same Location as Feature in Library" because they're at the same spots. To update them, edit the existing features or delete the old ones first, then import the new file.

### Scenario 3: Multiple Files with Overlap
You upload three files: "north_trails.geojson", "south_trails.geojson", and "all_trails.geojson" (which contains everything).

**Result**: 
- First file: Imports normally
- Second file: Imports normally (different trails)
- Third file: Features will show as "Exact Duplicate in Import Queue (Blocked)" or "Same Location as Feature in Import Queue" because they match the first two files

## Tips and Best Practices

**✓ Review the Warnings**: Always check which features are blocked before importing

**✓ Use the Links**: Click the links in duplicate warnings to see the existing features and confirm they're actually duplicates

**✓ Recheck After Changes**: If you import features from another file, use the "Recheck Duplicates" button to update the duplicate status of files still in your queue

**✓ Edit Instead of Re-import**: To update feature names, descriptions, or properties, edit the existing feature on your map rather than trying to import a new version

**✓ Delete First for Full Replacement**: If you want to completely replace features, delete the old ones from your library first, then import the new file

**✗ Don't Upload the Same File Twice**: GeoVault will block all features as exact duplicates

**✗ Can't Force Import Duplicates**: If a feature is blocked as a duplicate, there's no override button—this is by design to maintain data quality

## Technical Note on Priority

When a feature matches in multiple ways, GeoVault shows you the most relevant duplicate type:

- **Exact match takes priority** over same-location (if it's identical, that's what you'll see)
- **Library duplicates take priority** over queue duplicates (what's already on your map is most important)

This ensures you see the most important information first.
