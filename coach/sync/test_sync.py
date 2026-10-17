"""
Tests for the sync job, without network and without the Garmin library.

    python -m unittest discover -s coach/sync
"""

import json
import os
import sys
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path
from types import SimpleNamespace

os.environ["TZ"] = "Europe/Amsterdam"

import sync  # noqa: E402
from crypto_box import CryptoError, decrypt_json, encrypt_json  # noqa: E402

KEY = "test-sleutel-van-voldoende-lengte"


class CryptoTests(unittest.TestCase):
    def test_round_trip(self):
        value = {"hallo": "wereld", "getal": 3, "lijst": [1, 2, "drie"], "é": "ü"}
        envelope = encrypt_json(value, KEY, iterations=1000)
        self.assertEqual(envelope["format"], "coach-enc")
        self.assertNotIn("wereld", json.dumps(envelope))
        self.assertEqual(decrypt_json(envelope, KEY), value)

    def test_wrong_key_fails(self):
        envelope = encrypt_json({"a": 1}, KEY, iterations=1000)
        with self.assertRaises(CryptoError):
            decrypt_json(envelope, KEY + "x")

    def test_tampering_fails(self):
        envelope = encrypt_json({"a": 1}, KEY, iterations=1000)
        ct = bytearray(__import__("base64").b64decode(envelope["ct"]))
        ct[0] ^= 1
        envelope["ct"] = __import__("base64").b64encode(bytes(ct)).decode()
        with self.assertRaises(CryptoError):
            decrypt_json(envelope, KEY)

    def test_short_key_rejected(self):
        with self.assertRaises(CryptoError):
            encrypt_json({}, "kort")


class GarminExtractionTests(unittest.TestCase):
    summary = {
        "totalSteps": 11234,
        "dailyStepGoal": 9000,
        "totalKilocalories": 2650.0,
        "activeKilocalories": 780.0,
        "restingHeartRate": 48,
        "lastSevenDaysAvgRestingHeartRate": 50,
        "averageStressLevel": -1,
        "maxStressLevel": 88,
        "bodyBatteryHighestValue": 92,
        "bodyBatteryLowestValue": 21,
        "moderateIntensityMinutes": 25,
        "vigorousIntensityMinutes": 40,
    }
    sleep = {
        "dailySleepDTO": {
            "sleepTimeSeconds": 26100,
            "deepSleepSeconds": 5400,
            "lightSleepSeconds": 14400,
            "remSleepSeconds": 6300,
            "awakeSleepSeconds": 900,
            "sleepStartTimestampLocal": 1760650440000,
            "sleepEndTimestampLocal": 1760677440000,
            "sleepScores": {"overall": {"value": 84, "qualifierKey": "GOOD"}},
        },
        "avgOvernightHrv": 61,
    }
    hrv = {
        "hrvSummary": {
            "lastNightAvg": 63,
            "weeklyAvg": 59,
            "status": "BALANCED",
            "baseline": {"balancedLow": 52, "balancedUpper": 68},
        }
    }
    readiness = [
        {"timestamp": "2026-10-17T05:00:00.0", "score": 55, "level": "MODERATE"},
        {"timestamp": "2026-10-17T07:10:00.0", "score": 71, "level": "HIGH", "recoveryTime": 600},
    ]

    def test_day(self):
        day = sync.extract_garmin_day("2026-10-17", self.summary, self.sleep, self.hrv, self.readiness)
        self.assertEqual(day["steps"], 11234)
        self.assertEqual(day["rhr"], 48)
        self.assertNotIn("stressAvg", day, "Garmin's -1 means no data")
        self.assertEqual(day["stressMax"], 88)
        self.assertEqual(day["sleep"]["totalS"], 26100)
        self.assertEqual(day["sleep"]["score"], 84)
        self.assertEqual(day["sleep"]["start"], "2025-10-16T21:34")
        self.assertEqual(day["hrv"], {"lastNight": 63, "weeklyAvg": 59, "status": "BALANCED", "baselineLow": 52, "baselineHigh": 68})
        self.assertEqual(day["readiness"]["score"], 71, "latest entry wins")
        self.assertEqual(day["readiness"]["recoveryMin"], 600)

    def test_empty_day(self):
        self.assertEqual(sync.extract_garmin_day("2026-10-17", None, None, None, None), {"date": "2026-10-17"})

    def test_weights(self):
        body = {
            "dateWeightList": [
                {"calendarDate": "2026-10-15", "weight": 78450.0, "bmi": 23.41, "bodyFat": 17.2},
                {"calendarDate": "2026-10-15", "weight": 79000.0},
                {"date": 1760572800000, "weight": 78100.0},
                {"calendarDate": "2026-10-13", "weight": None},
            ]
        }
        self.assertEqual(
            sync.extract_weights(body),
            {
                "2026-10-15": {"weightKg": 78.5, "bmi": 23.4, "bodyFatPct": 17.2},
                "2025-10-16": {"weightKg": 78.1},
            },
        )

    def test_status(self):
        status = sync.extract_status(
            {
                "mostRecentVO2Max": {"generic": {"vo2MaxPreciseValue": 52.37}},
                "mostRecentTrainingStatus": {
                    "latestTrainingStatusData": {
                        "123": {
                            "primaryTrainingDevice": True,
                            "trainingStatusFeedbackPhrase": "PRODUCTIVE_1",
                            "acuteTrainingLoadDTO": {
                                "dailyTrainingLoadAcute": 612,
                                "dailyTrainingLoadChronic": 540,
                                "dailyAcuteChronicWorkloadRatio": 1.13,
                                "acwrStatus": "OPTIMAL",
                            },
                        }
                    }
                },
            },
            None,
            "2026-10-17",
        )
        self.assertEqual(status["vo2max"], 52.4)
        self.assertEqual(status["trainingStatus"], "PRODUCTIVE_1")
        self.assertEqual(status["acwr"], 1.13)

    def test_activity(self):
        a = sync.compact_garmin_activity(
            {
                "activityId": 99,
                "activityName": "Grootegast Hardlopen",
                "activityType": {"typeKey": "running"},
                "startTimeLocal": "2026-10-17 07:31:02",
                "startTimeGMT": "2026-10-17 05:31:02",
                "duration": 2712.4,
                "distance": 8012.3,
                "averageHR": 148.0,
                "aerobicTrainingEffect": 3.24,
                "activityTrainingLoad": 112.7,
            }
        )
        self.assertEqual(a["start"], "2026-10-17T07:31:02")
        self.assertEqual(a["startUtc"], "2026-10-17T05:31:02Z")
        self.assertEqual(a["aerobicTE"], 3.2)
        self.assertEqual(a["trainingLoad"], 113)

    def test_plan_backfills_gaps(self):
        today = date(2026, 10, 17)
        plan = sync.plan_garmin_days(today, [], 60)
        self.assertEqual(len(plan), sync.GARMIN_RECENT_DAYS + sync.GARMIN_BACKFILL_PER_RUN)
        self.assertEqual(plan[0], today)
        known = [d.isoformat() for d in plan]
        second = sync.plan_garmin_days(today, known, 60)
        self.assertEqual(second[:3], plan[:3], "recent days are always refreshed")
        self.assertTrue(set(second[3:]).isdisjoint(plan), "the backfill moves on")


class FakeResponse:
    def __init__(self, status, body):
        self.status_code = status
        self.ok = 200 <= status < 300
        self._body = body
        self.text = json.dumps(body)

    def json(self):
        return self._body


class FakeStrava:
    """Just enough of requests.Session to play Strava."""

    def __init__(self, activities, rotate_to=None):
        self.activities = activities
        self.rotate_to = rotate_to
        self.calls = []
        self.refresh_tokens = []

    def post(self, url, data=None, timeout=None):
        self.refresh_tokens.append(data["refresh_token"])
        return FakeResponse(200, {"access_token": "acc", "refresh_token": self.rotate_to or data["refresh_token"]})

    def get(self, url, headers=None, params=None, timeout=None):
        path = url.replace(sync.STRAVA_API, "")
        self.calls.append((path, params))
        if path == "/athlete":
            return FakeResponse(200, {"id": 7, "firstname": "Sam", "weight": 77.3})
        if path == "/athletes/7/stats":
            return FakeResponse(200, {"ytd_run_totals": {"count": 40, "distance": 412000, "moving_time": 150000}})
        if path == "/athlete/zones":
            return FakeResponse(403, {})
        if path == "/athlete/activities":
            after = params["after"]
            page = params["page"]
            items = [a for a in self.activities if datetime.fromisoformat(a["start_date"].replace("Z", "+00:00")).timestamp() >= after]
            return FakeResponse(200, items if page == 1 else [])
        if path.startswith("/activities/"):
            return FakeResponse(200, {"calories": 612, "description": "Lekker rondje", "laps": []})
        return FakeResponse(404, {})


def strava_activity(id_, day, name="Ochtendloop"):
    return {
        "id": id_,
        "name": name,
        "sport_type": "Run",
        "start_date": f"{day}T05:30:00Z",
        "start_date_local": f"{day}T07:30:00Z",
        "distance": 10000.0,
        "moving_time": 3000,
        "average_heartrate": 150.2,
    }


ENV = {
    "COACH_SYNC_KEY": KEY,
    "STRAVA_CLIENT_ID": "1",
    "STRAVA_CLIENT_SECRET": "geheim",
    "STRAVA_REFRESH_TOKEN": "refresh-uit-secret",
}


class RunTests(unittest.TestCase):
    def test_incremental_strava_sync(self):
        with tempfile.TemporaryDirectory() as tmp:
            first_dir, second_dir = Path(tmp) / "een", Path(tmp) / "twee"
            session = FakeStrava(
                [strava_activity(1, "2026-10-01"), strava_activity(2, "2026-10-15"), strava_activity(3, "2026-10-16")],
                rotate_to="refresh-nieuw",
            )
            now = datetime(2026, 10, 17, 6, 0, tzinfo=timezone.utc)
            args = SimpleNamespace(previous=None, out=str(first_dir), full=False, dry_run=False)
            self.assertEqual(sync.run(args, ENV, now=now, today=date(2026, 10, 17), session=session), 0)

            bundle = decrypt_json(json.loads((first_dir / sync.BUNDLE_FILE).read_text()), KEY)
            state = decrypt_json(json.loads((first_dir / sync.STATE_FILE).read_text()), KEY)
            self.assertEqual([a["id"] for a in bundle["strava"]["activities"]], [3, 2, 1])
            self.assertEqual(bundle["strava"]["activities"][0]["start"], "2026-10-16T07:30:00")
            self.assertEqual(bundle["strava"]["activities"][0]["calories"], 612)
            self.assertEqual(bundle["sources"]["garmin"], {"configured": False})
            self.assertTrue(bundle["sources"]["strava"]["ok"])
            self.assertEqual(state["strava"]["refresh_token"], "refresh-nieuw")
            self.assertNotIn("garmin", [p.name for p in first_dir.iterdir()], "no token files in the published folder")

            # Second run: activity 2 was deleted on Strava, 4 is new.
            session2 = FakeStrava([strava_activity(1, "2026-10-01"), strava_activity(3, "2026-10-16"), strava_activity(4, "2026-10-17")])
            args2 = SimpleNamespace(previous=str(first_dir), out=str(second_dir), full=False, dry_run=False)
            self.assertEqual(sync.run(args2, ENV, now=now, today=date(2026, 10, 17), session=session2), 0)
            bundle2 = decrypt_json(json.loads((second_dir / sync.BUNDLE_FILE).read_text()), KEY)
            self.assertEqual([a["id"] for a in bundle2["strava"]["activities"]], [4, 3, 1])
            self.assertEqual(session2.refresh_tokens, ["refresh-nieuw"], "the rotated token is used")
            detail_calls = [c for c in session2.calls if c[0].startswith("/activities/")]
            self.assertEqual(detail_calls, [("/activities/4", {"include_all_efforts": "false"})], "details are fetched once")

    def test_failing_source_keeps_old_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            first_dir, second_dir = Path(tmp) / "een", Path(tmp) / "twee"
            now = datetime(2026, 10, 17, 6, 0, tzinfo=timezone.utc)
            args = SimpleNamespace(previous=None, out=str(first_dir), full=False, dry_run=False)
            sync.run(args, ENV, now=now, today=date(2026, 10, 17), session=FakeStrava([strava_activity(1, "2026-10-10")]))

            class Broken(FakeStrava):
                def post(self, url, data=None, timeout=None):
                    return FakeResponse(400, {"message": "Bad Request"})

            args2 = SimpleNamespace(previous=str(first_dir), out=str(second_dir), full=False, dry_run=False)
            later = datetime(2026, 10, 17, 9, 0, tzinfo=timezone.utc)
            self.assertEqual(sync.run(args2, ENV, now=later, today=date(2026, 10, 17), session=Broken([])), 2)
            bundle = decrypt_json(json.loads((second_dir / sync.BUNDLE_FILE).read_text()), KEY)
            self.assertEqual([a["id"] for a in bundle["strava"]["activities"]], [1])
            self.assertFalse(bundle["sources"]["strava"]["ok"])
            self.assertEqual(bundle["sources"]["strava"]["lastSuccess"], "2026-10-17T06:00:00Z")

    def test_garmin_sync_persists_rotated_tokens(self):
        logins = []

        class FakeTokens:
            def __init__(self):
                self.tokens = None

            def dumps(self):
                return json.dumps({"di_token": "nieuw", "di_refresh_token": "nieuw-refresh"})

        class FakeGarmin:
            def __init__(self, email=None, password=None):
                self.client = FakeTokens()

            def login(self, tokenstore):
                logins.append(Path(tokenstore, "garmin_tokens.json").read_text())

            def get_user_summary(self, ds):
                return {"totalSteps": 8000 if ds == "2026-10-17" else 5000, "restingHeartRate": 50}

            def get_sleep_data(self, ds):
                return {"dailySleepDTO": {"sleepTimeSeconds": 25200}}

            def get_hrv_data(self, ds):
                return None

            def get_training_readiness(self, ds):
                raise RuntimeError("niet ondersteund door dit horloge")

            def get_body_composition(self, start, end):
                return {"dateWeightList": [{"calendarDate": "2026-10-16", "weight": 77000}]}

            def get_activities_by_date(self, start, end):
                return [{"activityId": 5, "activityType": {"typeKey": "running"}, "startTimeLocal": "2026-10-16 18:00:00"}]

            def get_training_status(self, ds):
                return {}

            def get_max_metrics(self, ds):
                return []

        fake_module = type(sys)("garminconnect")
        fake_module.Garmin = FakeGarmin
        sys.modules["garminconnect"] = fake_module
        self.addCleanup(sys.modules.pop, "garminconnect", None)

        env = {"COACH_SYNC_KEY": KEY, "GARMIN_TOKENS": '{"di_token": "uit-secret"}'}
        now = datetime(2026, 10, 17, 6, 0, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as tmp:
            first_dir, second_dir = Path(tmp) / "een", Path(tmp) / "twee"
            args = SimpleNamespace(previous=None, out=str(first_dir), full=False, dry_run=False)
            self.assertEqual(sync.run(args, env, now=now, today=date(2026, 10, 17)), 0)
            bundle = decrypt_json(json.loads((first_dir / sync.BUNDLE_FILE).read_text()), KEY)
            garmin = bundle["garmin"]
            today = garmin["days"][-1]
            self.assertEqual(today, {"date": "2026-10-17", "steps": 8000, "rhr": 50, "sleep": {"totalS": 25200}})
            self.assertEqual(next(d for d in garmin["days"] if d["date"] == "2026-10-16")["weightKg"], 77.0)
            self.assertEqual(garmin["activities"][0]["id"], 5)
            self.assertEqual(len(bundle["sources"]["garmin"]["warnings"]), 1, "one warning per kind, not per day")

            args2 = SimpleNamespace(previous=str(first_dir), out=str(second_dir), full=False, dry_run=False)
            sync.run(args2, env, now=now, today=date(2026, 10, 17))
            self.assertEqual(json.loads(logins[0])["di_token"], "uit-secret")
            self.assertEqual(json.loads(logins[1])["di_token"], "nieuw", "the refreshed tokens are reused")

            # A new secret means the user logged in again: it beats the saved state.
            env3 = dict(env, GARMIN_TOKENS='{"di_token": "opnieuw-ingelogd"}')
            args3 = SimpleNamespace(previous=str(second_dir), out=str(first_dir), full=False, dry_run=True)
            sync.run(args3, env3, now=now, today=date(2026, 10, 17))
            self.assertEqual(json.loads(logins[2])["di_token"], "opnieuw-ingelogd")

    def test_missing_key(self):
        args = SimpleNamespace(previous=None, out="niet-gebruikt", full=False, dry_run=True)
        self.assertEqual(sync.run(args, {"COACH_SYNC_KEY": "kort"}), 1)
        self.assertEqual(sync.run(args, {"STRAVA_CLIENT_ID": "1", "STRAVA_CLIENT_SECRET": "x", "STRAVA_REFRESH_TOKEN": "y"}), 1)

    def test_not_set_up_yet_is_not_a_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = SimpleNamespace(previous=None, out=tmp, full=False, dry_run=False)
            self.assertEqual(sync.run(args, {}), 0)
            self.assertEqual(list(Path(tmp).iterdir()), [], "nothing is published")


if __name__ == "__main__":
    unittest.main()
