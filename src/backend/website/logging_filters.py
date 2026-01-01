"""
Custom logging filters for Django.
"""
import logging


class SuppressCaltopoFilter(logging.Filter):
    """
    Filter to suppress logging messages from caltopo_python library.
    
    The caltopo_python library uses logging.info() directly (root logger)
    instead of using a named logger, so we need to filter by message content.
    """
    
    SUPPRESSED_PHRASES = [
        'Opening a CaltopoSession object',
        'CaltopoSession instance deleted',
        'about to create a new map',
        'Initial cache population',
        'Getting account data',
        'Caltopo sync',
        'Pausing sync',
        'Resuming sync',
    ]
    
    def filter(self, record):
        """
        Return False to suppress the log record, True to allow it.
        """
        # Check if this is a caltopo_python message by checking the message content
        message = record.getMessage()
        
        for phrase in self.SUPPRESSED_PHRASES:
            if phrase in message:
                return False
        
        # Also check if it's coming from the caltopo_python module
        if hasattr(record, 'pathname') and 'caltopo_python' in record.pathname:
            return False
            
        return True

