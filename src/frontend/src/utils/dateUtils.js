import moment from 'moment';

/**
 * Format a date string to a localized date and time string
 * @param {string} dateString - ISO date string or date string
 * @returns {string} Formatted date string in user's local timezone (e.g., "Jan 15, 2024 2:30 PM")
 */
export function formatDate(dateString) {
  if (!dateString) return '';
  try {
    // Use moment.js for localized date formatting
    // moment.js will automatically use the browser's locale if available
    return moment(dateString).format('MMM D, YYYY h:mm A'); // e.g., "Jan 15, 2024 2:30 PM"
  } catch (error) {
    console.error('Error formatting date:', error);
    return '';
  }
}

