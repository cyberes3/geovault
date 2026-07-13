"""
Tests for the ngs_data extension (NGS per-region SQLite download).
"""
import json
import os
import tempfile
import zipfile
from datetime import datetime, timezone
from io import StringIO
from pathlib import Path
from unittest.mock import MagicMock, patch

from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.core.management import call_command
from django.test import TestCase


def _patch_ngs_data_enabled() -> object:
    mock_config = MagicMock()
    mock_config.extension_settings.side_effect = (
        lambda name: {"enabled": True} if name == "ngs_data" else {}
    )
    return patch("website.extensions.extension_loader.get_config", return_value=mock_config)


@patch.dict(os.environ, {}, clear=False)
class TestNgsDataExtensionAPI(TestCase):
    """nominally requires ngs_data extension on disk and enabled in tests."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="ngs-test@example.com",
            password="testpass123",
            username="ngs-test",
        )
        self.client.force_login(self.user)

    def test_unauthenticated_401(self):
        self.client.logout()
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/download/",
                {"region": "CA"},
            )
        self.assertEqual(response.status_code, 401)

    def test_missing_region_400(self):
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/download/",
            )
        self.assertEqual(response.status_code, 400)
        self.assertIn(b"region", response.content)

    def test_unknown_region_404(self):
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/download/",
                {"region": "ZZ"},
            )
        self.assertEqual(response.status_code, 404)

    def test_download_serves_file_200(self):
        from extensions.ngs_data.src.backend import views as ngs_views

        with tempfile.TemporaryDirectory() as tmp:
            fpath = os.path.join(tmp, "CA.sqlite")
            with open(fpath, "wb") as f:
                f.write(b"test-sqlite-payload")
            with _patch_ngs_data_enabled(), patch.object(
                ngs_views, "_ngs_data_dir", return_value=Path(tmp)
            ):
                response = self.client.get(
                    "/api/extensions/ngs-data/download/",
                    {"region": "ca"},
                )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            b"".join(response.streaming_content),
            b"test-sqlite-payload",
        )
        self.assertIn("attachment", response.get("Content-Disposition", ""))
        self.assertEqual(
            response["CDN-Cache-Control"],
            f"public, max-age={ngs_views._NGS_DOWNLOAD_CDN_CACHE_MAX_AGE_SECONDS}",
        )
        self.assertEqual(response["Cache-Control"], "private, max-age=0")
        self.assertEqual(response["Vary"], "Authorization, Cookie")

    def test_missing_on_disk_404(self):
        with tempfile.TemporaryDirectory() as tmp, _patch_ngs_data_enabled(), patch(
            "extensions.ngs_data.src.backend.views._ngs_data_dir",
            return_value=__import__("pathlib").Path(tmp),
        ):
            response = self.client.get(
                "/api/extensions/ngs-data/download/",
                {"region": "CA"},
            )
        self.assertEqual(response.status_code, 404)

    def test_catalog_unauthenticated_401(self):
        self.client.logout()
        with _patch_ngs_data_enabled():
            response = self.client.get("/api/extensions/ngs-data/catalog/")
        self.assertEqual(response.status_code, 401)

    def test_catalog_empty_directory(self):
        from extensions.ngs_data.src.backend import views as ngs_views

        with tempfile.TemporaryDirectory() as tmp, _patch_ngs_data_enabled(), patch.object(
            ngs_views, "_ngs_data_dir", return_value=Path(tmp)
        ):
            response = self.client.get("/api/extensions/ngs-data/catalog/")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data.get("databases"), [])

    def test_catalog_lists_file_with_metadata(self):
        from extensions.ngs_data.src.backend import views as ngs_views

        payload = b"sqlite-bytes-here"
        with tempfile.TemporaryDirectory() as tmp:
            fpath = os.path.join(tmp, "CA.sqlite")
            with open(fpath, "wb") as f:
                f.write(payload)
            with _patch_ngs_data_enabled(), patch.object(
                ngs_views, "_ngs_data_dir", return_value=Path(tmp)
            ):
                response = self.client.get("/api/extensions/ngs-data/catalog/")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        dbs = data.get("databases")
        self.assertEqual(len(dbs), 1)
        self.assertEqual(
            dbs[0],
            {
                "id": "CA",
                "display_name": "California",
                "size_bytes": len(payload),
            },
        )
        self.assertEqual(
            response["CDN-Cache-Control"],
            f"public, max-age={ngs_views._NGS_CATALOG_CDN_CACHE_MAX_AGE_SECONDS}",
        )
        self.assertEqual(response["Cache-Control"], "private, max-age=0")
        self.assertEqual(response["Vary"], "Authorization, Cookie")

    def test_catalog_second_request_uses_server_cache(self):
        from extensions.ngs_data.src.backend import views as ngs_views

        payload = b"x"
        with tempfile.TemporaryDirectory() as tmp:
            fpath = os.path.join(tmp, "CA.sqlite")
            with open(fpath, "wb") as f:
                f.write(payload)
            p = Path(tmp)
            cache_key = f"{ngs_views._NGS_CATALOG_CACHE_PREFIX}:{p.resolve()}"
            cache.delete(cache_key)
            try:
                with _patch_ngs_data_enabled(), patch.object(
                    ngs_views, "_ngs_data_dir", return_value=p
                ):
                    r1 = self.client.get("/api/extensions/ngs-data/catalog/")
                    self.assertIsNotNone(cache.get(cache_key))
                    r2 = self.client.get("/api/extensions/ngs-data/catalog/")
            finally:
                cache.delete(cache_key)

        self.assertEqual(r1.status_code, 200)
        self.assertEqual(r2.status_code, 200)
        self.assertEqual(json.loads(r1.content), json.loads(r2.content))

    def test_datasheet_unauthenticated_401(self):
        self.client.logout()
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/datasheets/",
                {"station_id": "AB1234"},
            )
        self.assertEqual(response.status_code, 401)

    def test_datasheet_missing_station_id_400(self):
        with _patch_ngs_data_enabled():
            response = self.client.get("/api/extensions/ngs-data/datasheets/")
        self.assertEqual(response.status_code, 400)
        self.assertIn(b"station_id", response.content)

    def test_datasheet_invalid_station_id_400(self):
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/datasheets/",
                {"station_id": "BAD"},
            )
        self.assertEqual(response.status_code, 400)

    def test_datasheet_unknown_station_id_404(self):
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/datasheets/",
                {"station_id": "ZZ9999"},
            )
        self.assertEqual(response.status_code, 404)

    def test_datasheet_returns_cached_row(self):
        from extensions.ngs_data.src.backend import views as ngs_views
        from extensions.ngs_data.src.backend.models import NgsCachedDatasheet

        NgsCachedDatasheet.objects.create(
            station_id="AB1234",
            data_timestamp=datetime(2026, 4, 24, 21, 5, 8, tzinfo=timezone.utc),
            data_timestamp_raw="APRIL 24, 2026 21:05:08 UTC",
            data="DESIGNATION -  TEST MARK",
            source_region="AA",
            source_file="AA.ZIP:aa.txt",
        )
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/datasheets/",
                {"station_id": "ab1234"},
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            json.loads(response.content),
            [
                {
                    "timestamp": "2026-04-24T21:05:08+00:00",
                    "data": "DESIGNATION -  TEST MARK",
                }
            ],
        )
        self.assertEqual(
            response["CDN-Cache-Control"],
            f"public, max-age={ngs_views._NGS_DATASHEET_CDN_CACHE_MAX_AGE_SECONDS}",
        )
        self.assertEqual(response["Cache-Control"], "private, max-age=0")
        self.assertEqual(response["Vary"], "Authorization, Cookie")


class TestNgsDatasheetImport(TestCase):
    def _sample_archive_text(self, *, name: str = "FIRST") -> str:
        return f"""

                                  SURVEY CONTROL DATA

1    National Geodetic Survey, Retrieval Date = APRIL 24, 2026 21:05:08 EDT
 AB1234 ***********************************************************************
 AB1234  DESIGNATION -  {name}
 AB1234* NAD 83(2011) POSITION- 12 28 07.19358(N) 069 58 30.02601(W)   NO CHECK
 AB1234.Click photographs - Photos may exist for this station.

 CD5678 ***********************************************************************
 CD5678  DESIGNATION -  SECOND
 CD5678_MARKER: DD = SURVEY DISK
"""

    def test_parser_cleans_text_and_parses_timestamp(self):
        from extensions.ngs_data.src.backend.datasheet_text import NgsDatasheetArchiveParser

        parser = NgsDatasheetArchiveParser()
        parsed, invalid = parser.parse_text(
            self._sample_archive_text(),
            source_region="AA",
            source_file="AA.ZIP:aa.txt",
        )
        self.assertEqual(invalid, 0)
        self.assertEqual(len(parsed), 2)
        first = parsed[0]
        self.assertEqual(first.station_id, "AB1234")
        self.assertEqual(first.data_timestamp.isoformat(), "2026-04-24T21:05:08-04:00")
        self.assertEqual(first.data_timestamp_raw, "APRIL 24, 2026 21:05:08 EDT")
        self.assertIn("DESIGNATION -  FIRST", first.data)
        self.assertIn("NAD 83(2011) POSITION-", first.data)
        self.assertIn("Click photographs", first.data)
        self.assertNotIn("AB1234", first.data)

    def test_cleaner_handles_html_like_android_pdf_cleaner(self):
        from extensions.ngs_data.src.backend.datasheet_text import NgsDatasheetTextCleaner

        cleaner = NgsDatasheetTextCleaner()
        cleaned = cleaner.clean(
            "<html><pre> AB1234  DESIGNATION -  A&amp;B<br>"
            " AB1234.<a href=\"x\">Click photographs</a><br><br><br>"
            " AB1234_MARKER: DD</pre></html>",
            "AB1234",
        )
        self.assertEqual(
            cleaned,
            "DESIGNATION -  A&B\nClick photographs\n\nMARKER: DD",
        )

    def test_import_service_upserts_rows_without_deleting_unrelated_rows(self):
        from extensions.ngs_data.src.backend.datasheet_text import NgsDatasheetImportService
        from extensions.ngs_data.src.backend.models import NgsCachedDatasheet

        NgsCachedDatasheet.objects.create(
            station_id="ZZ9999",
            data_timestamp=datetime(2026, 1, 1, tzinfo=timezone.utc),
            data="unrelated",
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "AA.txt"
            path.write_text(self._sample_archive_text(name="FIRST"))
            service = NgsDatasheetImportService(batch_size=2)
            first = service.import_paths([path])
            path.write_text(self._sample_archive_text(name="UPDATED"))
            second = service.import_paths([path])

        self.assertEqual(first.created, 2)
        self.assertEqual(first.updated, 0)
        self.assertEqual(first.processed, 2)
        self.assertEqual(second.created, 0)
        self.assertEqual(second.updated, 2)
        self.assertEqual(second.processed, 2)
        self.assertEqual(NgsCachedDatasheet.objects.get(station_id="ZZ9999").data, "unrelated")
        self.assertIn("UPDATED", NgsCachedDatasheet.objects.get(station_id="AB1234").data)

    def test_import_service_reports_station_progress(self):
        from extensions.ngs_data.src.backend.datasheet_text import NgsDatasheetImportService

        progress_updates = []
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "AA.txt"
            path.write_text(self._sample_archive_text())
            result = NgsDatasheetImportService(batch_size=10).import_paths(
                [path],
                station_progress=progress_updates.append,
            )

        self.assertEqual(result.processed, 2)
        self.assertEqual(progress_updates, [1, 1])

    def test_import_service_keeps_last_duplicate_station_without_deleting_rows(self):
        from extensions.ngs_data.src.backend.datasheet_text import NgsDatasheetImportService
        from extensions.ngs_data.src.backend.models import NgsCachedDatasheet

        NgsCachedDatasheet.objects.create(
            station_id="ZZ9999",
            data_timestamp=datetime(2026, 1, 1, tzinfo=timezone.utc),
            data="unrelated",
        )
        duplicate_text = (
            f"{self._sample_archive_text(name='FIRST')}\n"
            f"{self._sample_archive_text(name='LAST')}"
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "AA.txt"
            path.write_text(duplicate_text)
            result = NgsDatasheetImportService(batch_size=10).import_paths([path])

        self.assertEqual(result.processed, 4)
        self.assertEqual(result.skipped, 2)
        self.assertEqual(
            NgsCachedDatasheet.objects.get(station_id="ZZ9999").data,
            "unrelated",
        )
        self.assertIn("LAST", NgsCachedDatasheet.objects.get(station_id="AB1234").data)

    def test_import_service_parallel_parse_preserves_document_order(self):
        from extensions.ngs_data.src.backend.datasheet_text import NgsDatasheetImportService
        from extensions.ngs_data.src.backend.models import NgsCachedDatasheet

        with tempfile.TemporaryDirectory() as tmp:
            first_path = Path(tmp) / "AA.txt"
            second_path = Path(tmp) / "AB.txt"
            first_path.write_text(self._sample_archive_text(name="FIRST"))
            second_path.write_text(self._sample_archive_text(name="LAST"))
            result = NgsDatasheetImportService(
                batch_size=10,
                parse_workers=2,
            ).import_paths([first_path, second_path])

        self.assertEqual(result.processed, 4)
        self.assertEqual(result.skipped, 2)
        self.assertIn("LAST", NgsCachedDatasheet.objects.get(station_id="AB1234").data)

    def test_import_service_reads_zip_archives(self):
        from extensions.ngs_data.src.backend.datasheet_text import NgsDatasheetImportService
        from extensions.ngs_data.src.backend.models import NgsCachedDatasheet

        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "AA.ZIP"
            with zipfile.ZipFile(zip_path, "w") as archive:
                archive.writestr("aa.txt", self._sample_archive_text())
            result = NgsDatasheetImportService(batch_size=10).import_paths([zip_path])

        self.assertEqual(result.created, 2)
        self.assertEqual(result.processed, 2)
        cached = NgsCachedDatasheet.objects.get(station_id="CD5678")
        self.assertEqual(cached.source_region, "AA")
        self.assertTrue(cached.source_file.endswith("AA.ZIP:aa.txt"))

    def test_archive_reader_counts_zip_documents_without_reading_payloads(self):
        from extensions.ngs_data.src.backend.datasheet_text import NgsDatasheetArchiveReader

        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "AA.ZIP"
            with zipfile.ZipFile(zip_path, "w") as archive:
                archive.writestr("aa.txt", "one")
                archive.writestr("nested/bb.dat", "two")
                archive.writestr(".ignored.txt", "three")
                archive.writestr("notes.md", "four")

            count = NgsDatasheetArchiveReader().count_documents([Path(tmp)])

        self.assertEqual(count, 2)

    def test_import_command_downloads_to_temp_then_imports(self):
        from extensions.ngs_data.src.backend.datasheet_text import (
            DatasheetImportResult,
            NgsDatasheetArchiveDownloadOutcome,
        )
        from extensions.ngs_data.src.backend.region_files import ALLOWED_REGION_KEYS

        test_case = self
        captured_paths = []

        def fake_download(_downloader, destination_dir, regions=None, progress=None):
            test_case.assertIsNone(regions)
            test_case.assertIsNotNone(progress)
            test_case.assertTrue(destination_dir.exists())
            archive_path = destination_dir / "AA.ZIP"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("aa.txt", "fake")
            for _ in ALLOWED_REGION_KEYS:
                progress(1)
            return NgsDatasheetArchiveDownloadOutcome((archive_path,), ())

        def fake_import_paths(_service, inputs, progress=None, station_progress=None):
            test_case.assertIsNotNone(progress)
            test_case.assertIsNotNone(station_progress)
            path_list = list(inputs)
            captured_paths.extend(path_list)
            test_case.assertEqual(len(path_list), 1)
            test_case.assertTrue(path_list[0].exists())
            return DatasheetImportResult(
                created=1,
                updated=2,
                skipped=3,
                invalid_blocks=4,
                processed=5,
            )

        out = StringIO()
        with patch(
            "extensions.ngs_data.src.backend.datasheet_text.NgsDatasheetArchiveDownloader.download_archives",
            fake_download,
        ), patch(
            "extensions.ngs_data.src.backend.datasheet_text.NgsDatasheetImportService.import_paths",
            fake_import_paths,
        ):
            call_command(
                "import_ngs_datasheets",
                stdout=out,
            )

        self.assertEqual(len(captured_paths), 1)
        self.assertFalse(captured_paths[0].exists())
        self.assertIn("Downloaded 1 NGS datasheet archives", out.getvalue())
        self.assertIn(
            "created=1 updated=2 skipped=3 invalid_blocks=4 processed=5",
            out.getvalue(),
        )
