/**
 * Simplification Tier Manager
 *
 * This utility class manages the 5-tier simplification system that dynamically adjusts
 * geometry simplification aggressiveness based on the number of polygon/line features
 * currently loaded on the map.
 *
 * ## How the Tier System Works:
 *
 * The system divides the maximum feature limit (5000) into 5 equal tiers:
 *
 * - **Tier 1 (0-1000 features)**: Minimal simplification (0.5x multiplier)
 *   - Used when few features are loaded
 *   - Preserves maximum detail for best visual quality
 *   - Ideal for detailed inspection of specific areas
 *
 * - **Tier 2 (1001-2000 features)**: Light simplification (1.0x multiplier)
 *   - Standard simplification level
 *   - Balanced quality vs performance
 *   - Good for moderate feature counts
 *
 * - **Tier 3 (2001-3000 features)**: Moderate simplification (2.0x multiplier)
 *   - More aggressive simplification
 *   - Maintains good visual quality while improving performance
 *   - Used when approaching higher feature counts
 *
 * - **Tier 4 (3001-4000 features)**: Aggressive simplification (4.0x multiplier)
 *   - Significant simplification for performance
 *   - Still maintains recognizable shapes
 *   - Used for large datasets
 *
 * - **Tier 5 (4001-5000 features)**: Maximum simplification (8.0x multiplier)
 *   - Most aggressive simplification
 *   - Prioritizes performance over detail
 *   - Used for very large datasets like complex geological maps
 *
 * ## Dynamic Behavior:
 *
 * - The system automatically detects when the feature count crosses tier thresholds
 * - When a tier change occurs, all existing features are re-simplified
 * - Only polygon and line features are counted (points are ignored)
 * - The system adapts in real-time as users pan/zoom and load new data
 */

import type {FeatureCountThresholds, SimplificationTier} from '@/types/geospatial';

export class SimplificationTierManager {
    private thresholds: FeatureCountThresholds;
    private maxFeatures: number;

    constructor(maxFeatures: number = 5000) {
        this.maxFeatures = maxFeatures;
        this.thresholds = this.initializeThresholds();
    }

    /**
     * Get the current simplification tier based on the number of polygon/line features
     * @param featureCount - Number of polygon and line features (points excluded)
     * @returns The current tier level (1-5)
     */
    getCurrentTier(featureCount: number): number {
        const {tier1, tier2, tier3, tier4} = this.thresholds;

        if (featureCount <= tier1) return 1;
        if (featureCount <= tier2) return 2;
        if (featureCount <= tier3) return 3;
        if (featureCount <= tier4) return 4;
        return 5;
    }

    /**
     * Get the simplification multiplier for a given tier
     * Higher tiers use more aggressive simplification (higher multipliers)
     * @param tier - The tier level (1-5)
     * @returns The simplification multiplier
     */
    getSimplificationMultiplier(tier: number): number {
        const multipliers: Record<number, number> = {
            1: 0.5,   // Minimal simplification (50% of base tolerance)
            2: 1.0,   // Light simplification (100% of base tolerance)
            3: 2.0,   // Moderate simplification (200% of base tolerance)
            4: 4.0,   // Aggressive simplification (400% of base tolerance)
            5: 8.0    // Maximum simplification (800% of base tolerance)
        };

        return multipliers[tier] || 1.0;
    }

    /**
     * Get detailed information about a specific tier
     * @param tier - The tier level (1-5)
     * @returns Detailed tier information
     */
    getTierInfo(tier: number): SimplificationTier {
        const tierInfo: Record<number, SimplificationTier> = {
            1: {
                level: 1,
                multiplier: 0.5,
                description: 'Minimal simplification',
                featureCountRange: `0-${this.thresholds.tier1} features`
            },
            2: {
                level: 2,
                multiplier: 1.0,
                description: 'Light simplification',
                featureCountRange: `${this.thresholds.tier1 + 1}-${this.thresholds.tier2} features`
            },
            3: {
                level: 3,
                multiplier: 2.0,
                description: 'Moderate simplification',
                featureCountRange: `${this.thresholds.tier2 + 1}-${this.thresholds.tier3} features`
            },
            4: {
                level: 4,
                multiplier: 4.0,
                description: 'Aggressive simplification',
                featureCountRange: `${this.thresholds.tier3 + 1}-${this.thresholds.tier4} features`
            },
            5: {
                level: 5,
                multiplier: 8.0,
                description: 'Maximum simplification',
                featureCountRange: `${this.thresholds.tier4 + 1}-${this.thresholds.tier5} features`
            }
        };

        return tierInfo[tier] || tierInfo[1];
    }

    /**
     * Get all tier information for debugging or display purposes
     * @returns Array of all tier information
     */
    getAllTierInfo(): SimplificationTier[] {
        return [1, 2, 3, 4, 5].map(tier => this.getTierInfo(tier));
    }

    /**
     * Get the current thresholds for debugging
     * @returns The current feature count thresholds
     */
    getThresholds(): FeatureCountThresholds {
        return {...this.thresholds};
    }

    /**
     * Update the maximum feature limit and recalculate thresholds
     * @param maxFeatures - New maximum feature limit
     */
    updateMaxFeatures(maxFeatures: number): void {
        this.maxFeatures = maxFeatures;
        this.thresholds = this.initializeThresholds();
    }

    /**
     * Initialize the 5-tier thresholds based on the maximum feature limit
     * Each tier represents 20% of the maximum features
     */
    private initializeThresholds(): FeatureCountThresholds {
        return {
            tier1: Math.floor(this.maxFeatures * 0.2),  // 0-20% of max features
            tier2: Math.floor(this.maxFeatures * 0.4),  // 20-40% of max features
            tier3: Math.floor(this.maxFeatures * 0.6),  // 40-60% of max features
            tier4: Math.floor(this.maxFeatures * 0.8),  // 60-80% of max features
            tier5: this.maxFeatures                     // 80-100% of max features
        };
    }
}
