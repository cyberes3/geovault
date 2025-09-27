import json
from django.core.management.base import BaseCommand
from data.models import ImportQueue


class Command(BaseCommand):
    help = 'Fix unparsable files that still have empty geofeatures to show they are finished processing'

    def handle(self, *args, **options):
        # Find unparsable files that still have empty geofeatures
        unparsable_files = ImportQueue.objects.filter(
            unparsable=True,
            geofeatures=[]
        )
        
        count = 0
        for item in unparsable_files:
            # Set geofeatures to indicate it's unparsable and finished
            error_geofeatures = [{"error": "unparsable", "message": "File was previously marked as unparsable"}]
            item.geofeatures = error_geofeatures
            item.save()
            count += 1
            self.stdout.write(f'Fixed unparsable file: {item.original_filename} (ID: {item.id})')
        
        self.stdout.write(
            self.style.SUCCESS(f'Successfully fixed {count} unparsable files')
        )
