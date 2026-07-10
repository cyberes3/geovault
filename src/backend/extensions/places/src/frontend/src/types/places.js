/**
 * @typedef {object} PlaceProperties
 * @property {number} database_id
 * @property {string} [name]
 * @property {string} [description]
 * @property {string} [address]
 * @property {string} [created_at]
 */

/**
 * @typedef {object} PlaceFeature
 * @property {'Feature'} type
 * @property {{ type: 'Point', coordinates: number[] }} geometry
 * @property {PlaceProperties} properties
 */

/**
 * @typedef {object} PlaceFeatureCollection
 * @property {'FeatureCollection'} type
 * @property {PlaceFeature[]} features
 */

export {};
