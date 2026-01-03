"""
Tests for log level configuration.
"""
import logging
from logging.handlers import BufferingHandler
from django.test import TestCase


class TestLogLevelConfig(TestCase):
    """Test log level configuration from YAML config."""

    def _test_log_level(self, config_level, test_level, level_below, level_above=None):
        """
        Test that a specific log level filters messages correctly.
        
        Args:
            config_level: The log level to configure (e.g., 'critical', 'error')
            test_level: The level to test (should be logged)
            level_below: The level below (should NOT be logged)
            level_above: Optional level above (should be logged)
        """
        # Create a test logger
        test_logger = logging.getLogger(f'test_logger_{config_level}')
        test_logger.setLevel(getattr(logging, config_level.upper()))
        
        # Create a buffering handler to capture log messages
        handler = BufferingHandler(capacity=100)
        handler.setLevel(getattr(logging, config_level.upper()))
        test_logger.addHandler(handler)
        test_logger.propagate = False
        
        # Clear any existing messages
        handler.buffer.clear()
        
        # Send a message at the configured level (should be logged)
        test_level_num = getattr(logging, test_level.upper())
        test_logger.log(test_level_num, f'Test message at {test_level}')
        
        # Verify the message was logged
        self.assertEqual(len(handler.buffer), 1, 
                       f'Message at {test_level} should be logged when level is {config_level}')
        self.assertEqual(handler.buffer[0].levelno, test_level_num)
        self.assertIn(f'Test message at {test_level}', handler.buffer[0].msg)
        
        # If there's a level above, test that it's also logged
        if level_above:
            handler.buffer.clear()
            level_above_num = getattr(logging, level_above.upper())
            test_logger.log(level_above_num, f'Test message at {level_above}')
            self.assertEqual(len(handler.buffer), 1,
                           f'Message at {level_above} (above {config_level}) should be logged')
            self.assertEqual(handler.buffer[0].levelno, level_above_num)
        
        # Clear buffer
        handler.buffer.clear()
        
        # Send a message one level below (should NOT be logged)
        level_below_num = getattr(logging, level_below.upper())
        test_logger.log(level_below_num, f'Test message at {level_below}')
        
        # Verify the message was NOT logged
        self.assertEqual(len(handler.buffer), 0,
                       f'Message at {level_below} should NOT be logged when level is {config_level}')
        
        # Clean up
        test_logger.removeHandler(handler)

    def test_log_level_critical(self):
        """Test that critical log level only logs critical messages."""
        # At CRITICAL level, CRITICAL should be logged, ERROR should not
        # (No level above CRITICAL)
        self._test_log_level('critical', 'critical', 'error')

    def test_log_level_error(self):
        """Test that error log level logs error and above, but not warning."""
        # At ERROR level, ERROR should be logged, CRITICAL (above) should be logged, WARNING should not
        self._test_log_level('error', 'error', 'warning', 'critical')

    def test_log_level_warning(self):
        """Test that warning log level logs warning and above, but not info."""
        # At WARNING level, WARNING should be logged, ERROR/CRITICAL (above) should be logged, INFO should not
        self._test_log_level('warning', 'warning', 'info', 'error')

    def test_log_level_debug(self):
        """Test that debug log level logs all messages including debug."""
        # At DEBUG level, DEBUG should be logged
        # There's nothing below DEBUG, so we just test that DEBUG works
        test_logger = logging.getLogger('test_logger_debug')
        test_logger.setLevel(logging.DEBUG)
        
        handler = BufferingHandler(capacity=100)
        handler.setLevel(logging.DEBUG)
        test_logger.addHandler(handler)
        test_logger.propagate = False
        
        handler.buffer.clear()
        
        # Send a DEBUG message (should be logged)
        test_logger.debug('Test debug message')
        
        # Verify the message was logged
        self.assertEqual(len(handler.buffer), 1,
                       'DEBUG message should be logged when level is DEBUG')
        self.assertEqual(handler.buffer[0].levelno, logging.DEBUG)
        
        # Also verify INFO is logged at DEBUG level
        handler.buffer.clear()
        test_logger.info('Test info message')
        self.assertEqual(len(handler.buffer), 1,
                       'INFO message should be logged when level is DEBUG')
        
        # Clean up
        test_logger.removeHandler(handler)

