/**
 * Style exports
 */

export { baseStyles, themeScript } from './base.js';
export {
  finderStyles,
  listViewStyles,
  gridViewStyles,
  previewModalStyles,
  getFinderStyles
} from './finder.js';
export {
  headerStyles,
  cardStyles,
  statStyles,
  nodeStyles,
  uploadStyles,
  searchStyles
} from './components.js';

import { baseStyles } from './base.js';
import {
  headerStyles,
  cardStyles,
  statStyles,
  nodeStyles,
  uploadStyles,
  searchStyles
} from './components.js';

/**
 * Get all styles for the main template pages (dashboard, upload)
 */
export function getMainStyles(): string {
  return baseStyles +
    headerStyles +
    cardStyles +
    statStyles +
    nodeStyles +
    uploadStyles +
    searchStyles;
}
