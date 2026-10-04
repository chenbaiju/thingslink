#!/usr/bin/env python3
"""A4-0 聚合判定回归；不启动 Java/Broker，专门锁住 PASS/FAIL 公式。"""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


SCRIPT = Path(__file__).with_name("a4_qualification.py")
SPEC = importlib.util.spec_from_file_location("a4_qualification", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class QualificationAggregationTests(unittest.TestCase):
    """验证机器判定不能因报告渲染或某个指标缺失而假绿。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.manifest = self.base / "manifests" / "run-1" / "shard-000"
        self.manifest.mkdir(parents=True)
        (self.manifest / "property_report.log").write_text("id-1\nid-2\n", encoding="utf-8")
        self.args = SimpleNamespace(
            ramp_deadline_seconds=300, properties_per_report=10, interval_seconds=60,
            shard_start_stagger_seconds=15,
            steady_seconds=300, sample_gap_limit_ms=2500, cpu_limit=0.70,
            resource_limit=0.70, rss_budget_bytes=1_073_741_824, thread_budget=1000,
            host_rss_budget_bytes=None, host_thread_budget=None,
            clock_limit_ms=100.0, device_count=1, shard_size=1, xms="256m", xmx="1g",
            broker_uri="tcp://127.0.0.1:1883", broker_fingerprint="test-broker",
            project_key="test-project",
            java="java", jar=SCRIPT)

    def tearDown(self):
        self.temp.cleanup()

    def stats(self):
        empty = {"initiated": 0, "confirmed": 0, "confirmedMessages": 0, "failed": 0}
        return {
            "runId": "run-1", "shardId": "shard-000", "propertiesPerReport": 10,
            "processCpuLoad": 0.1,
            "failedOperations": 0, "manifestHealthy": True, "manifestFailureReason": None,
            "manifestDir": str(self.manifest), "schedulingDeviationSampleCount": 5,
            "schedulingDeviationP99Millis": 100,
            "connection": {"target": 1, "attempted": 1, "succeeded": 1, "failed": 0,
                           "rampStartedAt": "2026-08-21T00:00:00Z",
                           "allConnectedAt": "2026-08-21T00:00:01Z", "rampDurationMillis": 1000},
            "generatorResources": {"sampleCount": 301, "maxSampleGapMillis": 1010,
                                   "maxSampleGapStartedAt": "2026-08-21T00:00:01Z",
                                   "maxSampleGapEndedAt": "2026-08-21T00:00:02.010Z",
                                   "incompleteSamples": 0, "peakProcessCpuLoad": 0.9,
                                   "maxFiveSecondCpuAverage": 0.4,
                                   "peakHeapUsedBytes": 100_000_000, "heapMaxBytes": 1_073_741_824,
                                   "peakRssBytes": 200_000_000, "peakThreadCount": 100,
                                   "peakOpenFileDescriptors": 20, "maxFileDescriptors": 1024},
            "propertyReports": {"initiated": 2, "confirmed": 2,
                                "confirmedMessages": 2, "failed": 0},
            "commandReplies": empty.copy(), "configReplies": empty.copy(),
            "batchReports": empty.copy(),
        }

    def aggregate(self, stats):
        shard = MODULE.Shard("shard-000", 18090, [{"deviceKey": "d1"}], None, None)
        clocks = [{"phase": "before", "offsetMillis": 10.0, "roundTripMillis": 5.0},
                  {"phase": "after", "offsetMillis": 20.0, "roundTripMillis": 5.0}]
        tracker = self.host_tracker([stats])
        return MODULE.aggregate(self.args, "run-1", [shard], [stats], clocks, tracker.report())

    @staticmethod
    def host_tracker(stats):
        tracker = MODULE.HostCpuTracker()
        for index in range(301):
            tracker.observe(stats, index * 1000.0)
        return tracker

    def test_all_frozen_formulas_produce_pass(self):
        report = self.aggregate(self.stats())
        self.assertEqual("PASS", report["result"])
        self.assertFalse([item for item in report["checks"] if not item["passed"]])

    def test_missing_resource_and_manifest_gap_produce_fail(self):
        stats = self.stats()
        stats["generatorResources"]["incompleteSamples"] = 1
        stats["generatorResources"]["maxFiveSecondCpuAverage"] = 0.71
        stats["propertyReports"]["confirmedMessages"] = 3
        report = self.aggregate(stats)
        failed = {item["name"] for item in report["checks"] if not item["passed"]}
        self.assertEqual("FAIL", report["result"])
        self.assertIn("shard-000.resourceSamples", failed)
        self.assertIn("shard-000.cpu", failed)
        self.assertIn("shard-000.propertyManifestLines", failed)

    def test_global_connection_ramp_includes_cross_shard_stagger(self):
        first = self.stats()
        second = self.stats()
        second["shardId"] = "shard-001"
        second["manifestDir"] = str(self.base / "manifests" / "run-1" / "shard-001")
        second_manifest = Path(second["manifestDir"])
        second_manifest.mkdir(parents=True)
        (second_manifest / "property_report.log").write_text("id-3\nid-4\n", encoding="utf-8")
        second["connection"] = {
            **second["connection"],
            "rampStartedAt": "2026-08-21T00:04:50Z",
            "allConnectedAt": "2026-08-21T00:05:01Z",
            "rampDurationMillis": 11_000,
        }
        shards = [
            MODULE.Shard("shard-000", 18090, [{"deviceKey": "d1"}], None, None),
            MODULE.Shard("shard-001", 18091, [{"deviceKey": "d2"}], None, None),
        ]
        clocks = [{"phase": "before", "offsetMillis": 10.0, "roundTripMillis": 5.0},
                  {"phase": "after", "offsetMillis": 20.0, "roundTripMillis": 5.0}]
        tracker = self.host_tracker([first, second])
        report = MODULE.aggregate(self.args, "run-1", shards, [first, second], clocks, tracker.report())
        failed = {item["name"] for item in report["checks"] if not item["passed"]}
        self.assertIn("aggregateConnectionRampDuration", failed)

    def test_shard_start_waits_its_frozen_stagger_before_http_start(self):
        shard = MODULE.Shard("shard-001", 18091, [{"deviceKey": "d2"}], None, None, 15.0)
        with mock.patch.object(MODULE.time, "sleep") as sleep, \
                mock.patch.object(MODULE, "http_json", return_value={}) as request:
            MODULE.start_shard(shard, self.args, "run-1")
        sleep.assert_called_once_with(15.0)
        self.assertEqual("POST", request.call_args.args[0])

    def test_ramp_resource_breach_is_detected_before_full_connection(self):
        stats = self.stats()
        stats["generatorResources"]["peakThreadCount"] = 701
        shard = MODULE.Shard("shard-000", 18090, [{"deviceKey": "d1"}], None, None)
        failures = MODULE.ramp_resource_failures(self.args, shard, stats)
        self.assertEqual(["shard-000.qualification.threads"], [item["name"] for item in failures])

    def test_ramp_ignores_unstarted_resource_sentinels_until_first_sample(self):
        stats = self.stats()
        resources = stats["generatorResources"]
        resources.update({"sampleCount": 0, "peakRssBytes": -1,
                          "peakOpenFileDescriptors": -1, "maxFileDescriptors": 65536})
        shard = MODULE.Shard("shard-000", 18090, [{"deviceKey": "d1"}], None, None)
        self.assertEqual([], MODULE.ramp_resource_failures(self.args, shard, stats))

    def test_ramp_rejects_resource_sentinels_after_sampling_started(self):
        stats = self.stats()
        resources = stats["generatorResources"]
        resources.update({"sampleCount": 1, "peakRssBytes": -1,
                          "peakOpenFileDescriptors": -1, "maxFileDescriptors": 65536})
        shard = MODULE.Shard("shard-000", 18090, [{"deviceKey": "d1"}], None, None)
        failures = MODULE.ramp_resource_failures(self.args, shard, stats)
        self.assertEqual(["shard-000.qualification.rss", "shard-000.qualification.fd"],
                         [item["name"] for item in failures])

    def test_host_aggregate_resource_breach_cannot_hide_behind_passing_shards(self):
        first = self.stats()
        second = self.stats()
        second["shardId"] = "shard-001"
        second["manifestDir"] = str(self.base / "manifests" / "run-1" / "shard-001")
        second_manifest = Path(second["manifestDir"])
        second_manifest.mkdir(parents=True)
        (second_manifest / "property_report.log").write_text("id-3\nid-4\n", encoding="utf-8")
        first["generatorResources"]["peakThreadCount"] = 600
        second["generatorResources"]["peakThreadCount"] = 600
        self.args.host_thread_budget = 1500
        shards = [
            MODULE.Shard("shard-000", 18090, [{"deviceKey": "d1"}], None, None),
            MODULE.Shard("shard-001", 18091, [{"deviceKey": "d2"}], None, None),
        ]
        clocks = [{"phase": "before", "offsetMillis": 10.0, "roundTripMillis": 5.0},
                  {"phase": "after", "offsetMillis": 20.0, "roundTripMillis": 5.0}]
        tracker = self.host_tracker([first, second])
        report = MODULE.aggregate(self.args, "run-1", shards, [first, second], clocks, tracker.report())
        failed = {item["name"] for item in report["checks"] if not item["passed"]}
        self.assertIn("hostAggregate.threads", failed)

    def test_host_cpu_uses_same_tick_sum_instead_of_sum_of_per_shard_peaks(self):
        first = self.stats()
        second = self.stats()
        first["generatorResources"]["maxFiveSecondCpuAverage"] = 0.6
        second["generatorResources"]["maxFiveSecondCpuAverage"] = 0.6
        first["processCpuLoad"] = 0.2
        second["processCpuLoad"] = 0.2
        tracker = self.host_tracker([first, second])
        self.assertAlmostEqual(0.4, tracker.report()["maxFiveSecondCpuAverage"])

    def test_host_cpu_rejects_concurrent_five_sample_breach(self):
        first = self.stats()
        second = self.stats()
        first["processCpuLoad"] = 0.4
        second["processCpuLoad"] = 0.4
        tracker = self.host_tracker([first, second])
        self.assertGreater(tracker.report()["maxFiveSecondCpuAverage"], self.args.cpu_limit)

    def test_host_cpu_archives_peak_window_phase_without_changing_formula(self):
        stats = [self.stats()]
        tracker = MODULE.HostCpuTracker()
        for index, load in enumerate((0.1, 0.2, 0.3, 0.4, 0.5)):
            stats[0]["processCpuLoad"] = load
            tracker.observe(stats, index * 1000.0, "connection-ramp")
        report = tracker.report()
        self.assertAlmostEqual(0.3, report["maxFiveSecondCpuAverage"])
        self.assertEqual("connection-ramp", report["maxFiveSecondCpuPhase"])
        self.assertEqual([0.1, 0.2, 0.3, 0.4, 0.5], report["maxFiveSecondCpuWindow"])

    def test_dual_signal_cannot_shorten_minimum_and_drain_is_only_stop_permission(self):
        performance = {"outcome": "COMPLETE", "submitted": 600}
        drain = {"outcome": "COMPLETE", "submitted": 600, "terminal": 600,
                 "succeeded": 600, "evidenceSha256": "a" * 64}
        self.assertEqual(
            "RUN", MODULE.coordination_status(99.0, 100.0, 130.0, 340.0, performance, drain))
        self.assertEqual(
            "PERFORMANCE_COMPLETE",
            MODULE.coordination_status(100.0, 100.0, 130.0, 340.0, performance, None))
        self.assertEqual(
            "COMPLETE", MODULE.coordination_status(100.0, 100.0, 130.0, 340.0, performance, drain))

    def test_dual_signal_timeouts_and_incomplete_drain_fail_closed(self):
        performance = {"outcome": "COMPLETE", "submitted": 600}
        incomplete = {"outcome": "TIMEOUT", "submitted": 600, "terminal": 598}
        self.assertEqual(
            "TIMEOUT", MODULE.coordination_status(131.0, 100.0, 130.0, None, None, None))
        self.assertEqual(
            "TIMEOUT", MODULE.coordination_status(341.0, 100.0, 130.0, 340.0, performance, None))
        self.assertEqual(
            "ABORT", MODULE.coordination_status(200.0, 100.0, 130.0, 340.0, performance, incomplete))

    def test_coordination_signal_rejects_wrong_run_and_phase(self):
        path = self.base / "performance-complete.json"
        path.write_text(json.dumps({"schemaVersion": 1, "runId": "other",
                                    "phase": "COMMAND_DRAIN_COMPLETE"}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "schema/runId/phase"):
            MODULE.read_coordination_signal(path, "run-1", "PERFORMANCE_COMPLETE")

    def test_legacy_and_cross_host_anonymous_identity_are_both_stable(self):
        legacy = MODULE.load_devices(SimpleNamespace(
            credentials_file=None, device_count=2, shard_id_prefix=None))
        host_a = MODULE.load_devices(SimpleNamespace(
            credentials_file=None, device_count=2, shard_id_prefix="host-a"))
        host_b = MODULE.load_devices(SimpleNamespace(
            credentials_file=None, device_count=2, shard_id_prefix="host-b"))
        self.assertEqual("a4_device_00000", legacy[0]["deviceKey"])
        self.assertEqual("qualification-only-00000", legacy[0]["accessToken"])
        self.assertTrue(set(item["deviceKey"] for item in host_a).isdisjoint(
            item["deviceKey"] for item in host_b))
        self.assertTrue(set(item["accessToken"] for item in host_a).isdisjoint(
            item["accessToken"] for item in host_b))
        self.assertEqual("shard-000", MODULE.build_shard_id(None, 0))
        self.assertEqual("host-a-shard-000", MODULE.build_shard_id("host-a", 0))


if __name__ == "__main__":
    unittest.main()
