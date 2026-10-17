#!/usr/bin/env python3
"""
Sync job for the coach app: Strava + Garmin Connect -> one encrypted bundle.

Neither service can be read straight from the phone. Strava's token endpoint
sends no CORS headers and needs the client secret, and Garmin has no personal
API at all. So this job does the fetching where secrets are safe - a scheduled
GitHub Action, see .github/workflows/coach-sync.yml - and publishes the result
encrypted on the `coach-data` branch, where the app picks it up.

    python coach/sync/sync.py --previous DIR --out DIR [--full] [--dry-run]

Environment (GitHub secrets; only the key is required):
    COACH_SYNC_KEY                    passphrase shared with the app
    STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET, STRAVA_REFRESH_TOKEN
    GARMIN_TOKENS                     token JSON from coach/tools/garmin_login.py
    GARMIN_EMAIL, GARMIN_PASSWORD     fallback login, only without two-step login
    COACH_HISTORY_DAYS                how far back to keep data (default 180)

The job is incremental: the previous bundle is decrypted and extended, so a
normal run only touches the last few days. Refresh tokens rotate (Garmin does,
Strava may), and a workflow cannot update its own secrets, so the newest
tokens live in a second encrypted file next to the bundle.
"""

import argparse
import json
import os
import shutil
import sys
import tempfile
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from crypto_box import CryptoError, decrypt_json, encrypt_json, fingerprint, normalise_key  # noqa: E402

BUNDLE_FILE = "bundle.enc.json"
STATE_FILE = "state.enc.json"
BUNDLE_VERSION = 1

STRAVA_TOKEN_URL = "https://www.strava.com/oauth/token"
STRAVA_API = "https://www.strava.com/api/v3"

DEFAULT_HISTORY_DAYS = 180
# Recent activities are read again so renames and deletions come through.
STRAVA_REFETCH_DAYS = 7
# Strava allows 100 reads per 15 minutes; detail calls are the bulk of them.
STRAVA_DETAIL_LIMIT = 40
# Watches sync late, so the last few days are always fetched again.
GARMIN_RECENT_DAYS = 3
GARMIN_BACKFILL_PER_RUN = 14
GARMIN_BACKFILL_FULL = 45
GARMIN_ACTIVITY_DAYS = 45
MAX_WARNINGS = 20

DETAIL_KEYS = ("calories", "description", "device", "laps", "splits", "bestEfforts", "detail")


class SyncError(Exception):
    pass


class RateLimited(SyncError):
    pass


def log(message):
    print(message, flush=True)


class Warnings:
    """Collects non-fatal problems, once per kind, so the log stays readable."""

    def __init__(self):
        self.items = []
        self._seen = set()

    def add(self, kind, message=None):
        if kind in self._seen:
            return
        self._seen.add(kind)
        if len(self.items) < MAX_WARNINGS:
            self.items.append(message or kind)


# --------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------


def drop_none(mapping):
    return {key: value for key, value in mapping.items() if value is not None}


def num(value, digits=None):
    """A finite number or None. Garmin uses -1/-2 for 'no data' in places."""
    if value is None or isinstance(value, bool):
        return None
    try:
        x = float(value)
    except (TypeError, ValueError):
        return None
    if x != x or x in (float("inf"), float("-inf")):
        return None
    if digits is None:
        return x
    if digits == 0:
        return int(round(x))
    return round(x, digits)


def nonneg(value, digits=0):
    x = num(value, digits)
    return x if x is not None and x >= 0 else None


def positive(value, digits=0):
    x = num(value, digits)
    return x if x is not None and x > 0 else None


def dig(obj, *path):
    for key in path:
        if isinstance(obj, dict):
            obj = obj.get(key)
        else:
            return None
    return obj


def short_error(err):
    text = str(err).strip().splitlines()[0] if str(err).strip() else type(err).__name__
    return text[:200]


def iso_utc(moment):
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_utc(text):
    if not text:
        return None
    try:
        return datetime.fromisoformat(str(text).replace("Z", "+00:00"))
    except ValueError:
        return None


def local_stamp(epoch_ms):
    """Garmin's *TimestampLocal fields are wall-clock time encoded as UTC."""
    x = num(epoch_ms)
    if not x:
        return None
    return datetime.fromtimestamp(x / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M")


def first_entry(mapping):
    """Garmin keys some objects by device id; prefer the primary device."""
    if not isinstance(mapping, dict) or not mapping:
        return {}
    values = [v for v in mapping.values() if isinstance(v, dict)]
    for value in values:
        if value.get("primaryTrainingDevice"):
            return value
    return values[0] if values else {}


# --------------------------------------------------------------------------
# Strava
# --------------------------------------------------------------------------


def strava_configured(env):
    return all((env.get(k) or "").strip() for k in ("STRAVA_CLIENT_ID", "STRAVA_CLIENT_SECRET", "STRAVA_REFRESH_TOKEN"))


def compact_strava_activity(a):
    start = a.get("start_date_local")
    # Strava marks local time with a misleading trailing Z.
    if isinstance(start, str) and start.endswith("Z"):
        start = start[:-1]
    return drop_none(
        {
            "id": a.get("id"),
            "name": a.get("name"),
            "sport": a.get("sport_type") or a.get("type"),
            "start": start,
            "startUtc": a.get("start_date"),
            "distanceM": nonneg(a.get("distance")),
            "movingS": nonneg(a.get("moving_time")),
            "elapsedS": nonneg(a.get("elapsed_time")),
            "elevationM": nonneg(a.get("total_elevation_gain")),
            "avgSpeed": positive(a.get("average_speed"), 3),
            "maxSpeed": positive(a.get("max_speed"), 3),
            "avgHr": positive(a.get("average_heartrate")),
            "maxHr": positive(a.get("max_heartrate")),
            "avgWatts": positive(a.get("average_watts")),
            "weightedWatts": positive(a.get("weighted_average_watts")),
            "kj": positive(a.get("kilojoules")),
            "avgCadence": positive(a.get("average_cadence")),
            "sufferScore": positive(a.get("suffer_score")),
            "trainer": True if a.get("trainer") else None,
            "commute": True if a.get("commute") else None,
            "manual": True if a.get("manual") else None,
            "workoutType": a.get("workout_type"),
            "prCount": a.get("pr_count") or None,
            "gearId": a.get("gear_id"),
        }
    )


def strava_detail_fields(detail, sport):
    laps = [
        drop_none(
            {
                "name": lap.get("name"),
                "distanceM": nonneg(lap.get("distance")),
                "movingS": nonneg(lap.get("moving_time")),
                "avgSpeed": positive(lap.get("average_speed"), 3),
                "avgHr": positive(lap.get("average_heartrate")),
                "avgWatts": positive(lap.get("average_watts")),
            }
        )
        for lap in (detail.get("laps") or [])[:40]
    ]
    on_foot = any(word in (sport or "") for word in ("Run", "Walk", "Hike"))
    splits = (
        [
            drop_none(
                {
                    "km": split.get("split"),
                    "distanceM": nonneg(split.get("distance")),
                    "movingS": nonneg(split.get("moving_time")),
                    "avgSpeed": positive(split.get("average_speed"), 3),
                    "avgHr": positive(split.get("average_heartrate")),
                    "elevDiffM": num(split.get("elevation_difference"), 0),
                }
            )
            for split in (detail.get("splits_metric") or [])[:60]
        ]
        if on_foot
        else []
    )
    efforts = [
        drop_none({"name": e.get("name"), "movingS": nonneg(e.get("moving_time")), "prRank": e.get("pr_rank")})
        for e in (detail.get("best_efforts") or [])
    ]
    description = (detail.get("description") or "").strip()[:1000]
    return drop_none(
        {
            "calories": positive(detail.get("calories")),
            "description": description or None,
            "device": detail.get("device_name"),
            "laps": laps if len(laps) > 1 else None,
            "splits": splits or None,
            "bestEfforts": efforts or None,
            "detail": True,
        }
    )


def compact_athlete(a):
    return drop_none(
        {
            "id": a.get("id"),
            "firstname": a.get("firstname"),
            "lastname": a.get("lastname"),
            "sex": a.get("sex"),
            "city": a.get("city"),
            "country": a.get("country"),
            "weightKg": positive(a.get("weight"), 1),
            "ftp": positive(a.get("ftp")),
            "units": a.get("measurement_preference"),
        }
    )


def compact_totals(stats):
    out = {}
    for sport in ("run", "ride", "swim"):
        for period in ("recent", "ytd", "all"):
            block = stats.get(f"{period}_{sport}_totals") or {}
            if not block.get("count"):
                continue
            out[f"{period}{sport.capitalize()}"] = drop_none(
                {
                    "count": block.get("count"),
                    "distanceM": nonneg(block.get("distance")),
                    "movingS": nonneg(block.get("moving_time")),
                    "elevationM": nonneg(block.get("elevation_gain")),
                }
            )
    out["biggestRideM"] = positive(stats.get("biggest_ride_distance"))
    out["biggestClimbM"] = positive(stats.get("biggest_climb_elevation_gain"))
    return drop_none(out)


class StravaApi:
    def __init__(self, session, access_token):
        self.session = session
        self.headers = {"Authorization": f"Bearer {access_token}"}

    def get(self, path, params=None):
        response = self.session.get(f"{STRAVA_API}{path}", headers=self.headers, params=params, timeout=30)
        if response.status_code == 429:
            raise RateLimited("Strava: limiet voor API-verzoeken bereikt")
        if response.status_code == 401:
            raise SyncError(
                "Strava weigert het token (401). Maak het token opnieuw met "
                "`npm run coach:strava-login` en vink alle rechten aan."
            )
        if not response.ok:
            raise SyncError(f"Strava {path}: {response.status_code} {response.text[:160]}")
        return response.json()


def strava_access_token(session, env, state):
    client_id = env["STRAVA_CLIENT_ID"].strip()
    client_secret = env["STRAVA_CLIENT_SECRET"].strip()
    secret_token = env["STRAVA_REFRESH_TOKEN"].strip()
    seed = fingerprint(secret_token)
    saved = state.get("strava") or {}

    candidates = []
    if saved.get("seed") == seed and saved.get("refresh_token"):
        candidates.append(saved["refresh_token"])
    if secret_token not in candidates:
        candidates.append(secret_token)

    last = None
    for token in candidates:
        response = session.post(
            STRAVA_TOKEN_URL,
            data={
                "client_id": client_id,
                "client_secret": client_secret,
                "grant_type": "refresh_token",
                "refresh_token": token,
            },
            timeout=30,
        )
        if response.ok:
            data = response.json()
            # Strava may hand out a new refresh token; the old one then dies.
            state["strava"] = {"seed": seed, "refresh_token": data.get("refresh_token") or token}
            return data["access_token"]
        last = response
    raise SyncError(
        f"Strava gaf {last.status_code} bij het vernieuwen van het token. Kloppen "
        "STRAVA_CLIENT_ID en STRAVA_CLIENT_SECRET? Anders opnieuw `npm run coach:strava-login`."
    )


def activity_epoch(activity):
    moment = parse_utc(activity.get("startUtc"))
    return moment.timestamp() if moment else None


def sync_strava(session, env, previous, state, today, history_days, full):
    warnings = Warnings()
    api = StravaApi(session, strava_access_token(session, env, state))

    athlete = api.get("/athlete")
    out = {"athlete": compact_athlete(athlete)}
    try:
        out["totals"] = compact_totals(api.get(f"/athletes/{athlete['id']}/stats"))
    except RateLimited:
        raise
    except SyncError as err:
        warnings.add("totals", f"Totalen niet opgehaald: {short_error(err)}")
    try:
        zones = api.get("/athlete/zones")
        hr = dig(zones, "heart_rate", "zones") or []
        if hr:
            out["hrZones"] = [drop_none({"min": z.get("min"), "max": z.get("max")}) for z in hr]
    except RateLimited:
        raise
    except SyncError:
        warnings.add("zones", "Hartslagzones niet beschikbaar (recht profile:read_all ontbreekt?).")

    window_start = today - timedelta(days=history_days)
    known = {a["id"]: a for a in (previous or {}).get("activities") or [] if a.get("id") is not None}
    after_day = window_start
    if known and not full:
        latest = max((a.get("start") or "")[:10] for a in known.values())
        try:
            latest_day = date.fromisoformat(latest)
            after_day = max(window_start, min(latest_day, today) - timedelta(days=STRAVA_REFETCH_DAYS))
        except ValueError:
            pass
    after_epoch = int(datetime.combine(after_day, time.min).timestamp())

    fetched = []
    for page in range(1, 21):
        batch = api.get("/athlete/activities", {"after": after_epoch, "per_page": 200, "page": page})
        if not batch:
            break
        fetched.extend(batch)
        if len(batch) < 200:
            break

    merged = {
        key: value
        for key, value in known.items()
        # Anything in the re-read range that Strava no longer lists was deleted.
        if (activity_epoch(value) or 0) < after_epoch
    }
    for raw in fetched:
        activity = compact_strava_activity(raw)
        old = known.get(activity.get("id")) or {}
        for key in DETAIL_KEYS:
            if key in old:
                activity.setdefault(key, old[key])
        merged[activity["id"]] = activity

    cutoff = window_start.isoformat()
    activities = sorted(
        (a for a in merged.values() if (a.get("start") or "")[:10] >= cutoff),
        key=lambda a: a.get("start") or "",
        reverse=True,
    )

    fetched_details = 0
    for activity in activities:
        if activity.get("detail"):
            continue
        if fetched_details >= STRAVA_DETAIL_LIMIT:
            warnings.add("detail-limit", "Nog niet alle details opgehaald; de rest volgt bij de volgende run.")
            break
        try:
            detail = api.get(f"/activities/{activity['id']}", {"include_all_efforts": "false"})
        except RateLimited:
            warnings.add("rate", "Strava-limiet bereikt; details volgen bij de volgende run.")
            break
        except SyncError as err:
            warnings.add("detail-error", f"Details van een activiteit mislukt: {short_error(err)}")
            continue
        activity.update(strava_detail_fields(detail, activity.get("sport")))
        fetched_details += 1

    out["activities"] = activities
    log(f"Strava: {len(activities)} activiteiten ({len(fetched)} opnieuw gelezen, {fetched_details} met nieuwe details).")
    return out, warnings.items


# --------------------------------------------------------------------------
# Garmin Connect
# --------------------------------------------------------------------------


def garmin_configured(env):
    return bool((env.get("GARMIN_TOKENS") or "").strip() or (env.get("GARMIN_EMAIL") and env.get("GARMIN_PASSWORD")))


def plan_garmin_days(today, known_dates, history_days, *, full=False):
    """Which calendar days to fetch this run: the recent ones, plus a slice of
    the gaps further back so the history fills up over a few runs."""
    window = [today - timedelta(days=i) for i in range(history_days)]
    plan = window[:GARMIN_RECENT_DAYS]
    known = set() if full else set(known_dates)
    missing = [d for d in window[GARMIN_RECENT_DAYS:] if d.isoformat() not in known]
    plan += missing[: GARMIN_BACKFILL_FULL if full else GARMIN_BACKFILL_PER_RUN]
    return plan


def latest_readiness(value):
    entries = value if isinstance(value, list) else [value] if isinstance(value, dict) else []
    entries = [e for e in entries if isinstance(e, dict) and e.get("score") is not None]
    if not entries:
        return None
    entry = max(entries, key=lambda e: str(e.get("timestamp") or e.get("timestampLocal") or ""))
    return drop_none(
        {
            "score": nonneg(entry.get("score")),
            "level": entry.get("level"),
            "feedback": entry.get("feedbackShort"),
            "recoveryMin": nonneg(entry.get("recoveryTime")),
        }
    ) or None


def extract_garmin_day(day, summary, sleep, hrv, readiness):
    s = summary if isinstance(summary, dict) else {}
    dto = dig(sleep, "dailySleepDTO") or {}
    hs = dig(hrv, "hrvSummary") or {}

    sleep_total = positive(dto.get("sleepTimeSeconds"))
    sleep_block = (
        drop_none(
            {
                "totalS": sleep_total,
                "deepS": nonneg(dto.get("deepSleepSeconds")),
                "lightS": nonneg(dto.get("lightSleepSeconds")),
                "remS": nonneg(dto.get("remSleepSeconds")),
                "awakeS": nonneg(dto.get("awakeSleepSeconds")),
                "napS": positive(dto.get("napTimeSeconds")),
                "score": nonneg(dig(dto, "sleepScores", "overall", "value")),
                "quality": dig(dto, "sleepScores", "overall", "qualifierKey"),
                "start": local_stamp(dto.get("sleepStartTimestampLocal")),
                "end": local_stamp(dto.get("sleepEndTimestampLocal")),
                "respAvg": positive(dto.get("averageRespirationValue"), 1),
                "spo2Avg": positive(dto.get("averageSpO2Value")),
                "stressAvg": nonneg(dto.get("avgSleepStress")),
            }
        )
        if sleep_total
        else None
    )

    hrv_block = drop_none(
        {
            "lastNight": positive(hs.get("lastNightAvg")) or positive(dig(sleep, "avgOvernightHrv")),
            "weeklyAvg": positive(hs.get("weeklyAvg")),
            "status": hs.get("status") or dig(sleep, "hrvStatus"),
            "baselineLow": positive(dig(hs, "baseline", "balancedLow")),
            "baselineHigh": positive(dig(hs, "baseline", "balancedUpper")),
        }
    )

    return drop_none(
        {
            "date": day,
            "steps": nonneg(s.get("totalSteps")),
            "stepGoal": positive(s.get("dailyStepGoal")),
            "kcalTotal": positive(s.get("totalKilocalories")),
            "kcalActive": nonneg(s.get("activeKilocalories")),
            "distanceM": nonneg(s.get("totalDistanceMeters")),
            "floors": nonneg(s.get("floorsAscended")),
            "intensityModerate": nonneg(s.get("moderateIntensityMinutes")),
            "intensityVigorous": nonneg(s.get("vigorousIntensityMinutes")),
            "rhr": positive(s.get("restingHeartRate")) or positive(dig(sleep, "restingHeartRate")),
            "rhr7d": positive(s.get("lastSevenDaysAvgRestingHeartRate")),
            "hrMin": positive(s.get("minHeartRate")),
            "hrMax": positive(s.get("maxHeartRate")),
            "stressAvg": nonneg(s.get("averageStressLevel")),
            "stressMax": nonneg(s.get("maxStressLevel")),
            "bbHigh": nonneg(s.get("bodyBatteryHighestValue")),
            "bbLow": nonneg(s.get("bodyBatteryLowestValue")),
            "bbNow": nonneg(s.get("bodyBatteryMostRecentValue")),
            "bbCharged": nonneg(s.get("bodyBatteryChargedValue")),
            "bbDrained": nonneg(s.get("bodyBatteryDrainedValue")),
            "spo2Avg": positive(s.get("averageSpo2")),
            "respAvg": positive(s.get("avgWakingRespirationValue"), 1),
            "sedentaryS": nonneg(s.get("sedentarySeconds")),
            "activeS": nonneg(s.get("activeSeconds")),
            "highlyActiveS": nonneg(s.get("highlyActiveSeconds")),
            "sleep": sleep_block,
            "hrv": hrv_block or None,
            "readiness": latest_readiness(readiness),
        }
    )


def extract_weights(body):
    out = {}
    for entry in dig(body, "dateWeightList") or []:
        if not isinstance(entry, dict):
            continue
        day = entry.get("calendarDate")
        if not day and num(entry.get("date")):
            day = datetime.fromtimestamp(num(entry["date"]) / 1000, tz=timezone.utc).date().isoformat()
        grams = positive(entry.get("weight"))
        if not day or not grams or day in out:
            continue
        muscle = positive(entry.get("muscleMass"))
        out[day] = drop_none(
            {
                "weightKg": round(grams / 1000, 1),
                "bmi": positive(entry.get("bmi"), 1),
                "bodyFatPct": positive(entry.get("bodyFat"), 1),
                "bodyWaterPct": positive(entry.get("bodyWater"), 1),
                "muscleKg": round(muscle / 1000, 1) if muscle else None,
            }
        )
    return out


def extract_status(training_status, max_metrics, as_of):
    ts = training_status if isinstance(training_status, dict) else {}
    vo2 = dig(ts, "mostRecentVO2Max", "generic") or {}
    vo2_cycling = dig(ts, "mostRecentVO2Max", "cycling") or {}
    latest = first_entry(dig(ts, "mostRecentTrainingStatus", "latestTrainingStatusData"))
    acute = latest.get("acuteTrainingLoadDTO") or {}
    balance = first_entry(dig(ts, "mostRecentTrainingLoadBalance", "metricsTrainingLoadBalanceDTOMap"))

    metrics = max_metrics[0] if isinstance(max_metrics, list) and max_metrics else max_metrics
    generic = dig(metrics, "generic") or {}

    return drop_none(
        {
            "asOf": as_of,
            "vo2max": positive(vo2.get("vo2MaxPreciseValue") or vo2.get("vo2MaxValue"), 1)
            or positive(generic.get("vo2MaxPreciseValue") or generic.get("vo2MaxValue"), 1),
            "vo2maxCycling": positive(vo2_cycling.get("vo2MaxPreciseValue") or vo2_cycling.get("vo2MaxValue"), 1),
            "fitnessAge": positive(generic.get("fitnessAge")),
            "trainingStatus": latest.get("trainingStatusFeedbackPhrase"),
            "acuteLoad": nonneg(acute.get("dailyTrainingLoadAcute")),
            "chronicLoad": nonneg(acute.get("dailyTrainingLoadChronic")),
            "acwr": positive(acute.get("dailyAcuteChronicWorkloadRatio"), 2),
            "acwrStatus": acute.get("acwrStatus"),
            "loadBalance": balance.get("trainingBalanceFeedbackPhrase"),
            "loadAerobicLow": nonneg(balance.get("monthlyLoadAerobicLow")),
            "loadAerobicHigh": nonneg(balance.get("monthlyLoadAerobicHigh")),
            "loadAnaerobic": nonneg(balance.get("monthlyLoadAnaerobic")),
        }
    )


def compact_garmin_activity(a):
    start = a.get("startTimeLocal")
    start_gmt = a.get("startTimeGMT")
    return drop_none(
        {
            "id": a.get("activityId"),
            "name": a.get("activityName"),
            "type": dig(a, "activityType", "typeKey"),
            "start": start.replace(" ", "T") if isinstance(start, str) else None,
            "startUtc": f"{start_gmt.replace(' ', 'T')}Z" if isinstance(start_gmt, str) else None,
            "durationS": nonneg(a.get("duration")),
            "movingS": nonneg(a.get("movingDuration")),
            "distanceM": nonneg(a.get("distance")),
            "elevationM": nonneg(a.get("elevationGain")),
            "avgSpeed": positive(a.get("averageSpeed"), 3),
            "avgHr": positive(a.get("averageHR")),
            "maxHr": positive(a.get("maxHR")),
            "calories": positive(a.get("calories")),
            "avgPower": positive(a.get("avgPower")),
            "normPower": positive(a.get("normPower")),
            "aerobicTE": nonneg(a.get("aerobicTrainingEffect"), 1),
            "anaerobicTE": nonneg(a.get("anaerobicTrainingEffect"), 1),
            "trainingLoad": nonneg(a.get("activityTrainingLoad")),
            "teLabel": a.get("trainingEffectLabel"),
            "vo2max": positive(a.get("vO2MaxValue")),
            "cadence": positive(a.get("averageRunningCadenceInStepsPerMinute")),
            "sets": positive(a.get("totalSets")),
            "reps": positive(a.get("totalReps")),
        }
    )


def is_rate_limit(err):
    return "TooManyRequests" in type(err).__name__ or "429" in str(err)


def write_private(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(text)


def garmin_login(env, state, workdir):
    from garminconnect import Garmin

    secret = (env.get("GARMIN_TOKENS") or "").strip()
    seed = fingerprint(secret) if secret else None
    saved = state.get("garmin") or {}

    candidates = []
    if saved.get("tokens") and saved.get("seed") == seed:
        candidates.append(saved["tokens"])
    if secret and secret not in candidates:
        candidates.append(secret)
    if not candidates:
        candidates.append(None)

    tokendir = Path(workdir) / "garmin"
    tokendir.mkdir(mode=0o700, parents=True, exist_ok=True)
    tokenfile = tokendir / "garmin_tokens.json"

    last = None
    for tokens in candidates:
        if tokens:
            write_private(tokenfile, tokens)
        elif tokenfile.exists():
            tokenfile.unlink()
        client = Garmin(env.get("GARMIN_EMAIL") or None, env.get("GARMIN_PASSWORD") or None)
        try:
            # A path (not inline JSON) so refreshed tokens are written back.
            client.login(str(tokendir))
            return client, seed
        except Exception as err:  # noqa: BLE001 - the library raises several unrelated types
            last = err
    raise SyncError(
        f"Inloggen bij Garmin mislukt ({short_error(last)}). Maak nieuwe tokens met "
        "`npm run coach:garmin-login` en zet ze in het secret GARMIN_TOKENS."
    )


def fetch_garmin_day(client, day, warnings):
    ds = day.isoformat()

    def safe(kind, call):
        try:
            return call()
        except Exception as err:  # noqa: BLE001
            if is_rate_limit(err):
                raise RateLimited("Garmin: te veel verzoeken") from err
            warnings.add(kind, f"Garmin {kind} niet opgehaald: {short_error(err)}")
            return None

    summary = safe("dagoverzicht", lambda: client.get_user_summary(ds))
    sleep = safe("slaap", lambda: client.get_sleep_data(ds))
    hrv = safe("hrv", lambda: client.get_hrv_data(ds))
    readiness = safe("trainingsgereedheid", lambda: client.get_training_readiness(ds))
    return extract_garmin_day(ds, summary, sleep, hrv, readiness)


def sync_garmin(env, previous, state, today, history_days, full, workdir):
    warnings = Warnings()
    client, seed = garmin_login(env, state, workdir)
    previous = previous or {}

    days = {d["date"]: d for d in previous.get("days") or [] if d.get("date")}
    plan = plan_garmin_days(today, days.keys(), history_days, full=full)
    fetched = 0
    for day in plan:
        try:
            fresh = fetch_garmin_day(client, day, warnings)
        except RateLimited:
            warnings.add("rate", "Garmin-limiet bereikt; de rest volgt bij de volgende run.")
            break
        old = days.get(fresh["date"]) or {}
        # Weight comes from a separate call below; keep what we had.
        for key in ("weightKg", "bmi", "bodyFatPct", "bodyWaterPct", "muscleKg"):
            if key in old:
                fresh.setdefault(key, old[key])
        # A day with nothing in it is still recorded, so the backfill moves on.
        days[fresh["date"]] = fresh
        fetched += 1

    window_start = today - timedelta(days=history_days)

    def safe(kind, call):
        try:
            return call()
        except Exception as err:  # noqa: BLE001
            warnings.add(kind, f"Garmin {kind} niet opgehaald: {short_error(err)}")
            return None

    body = safe("gewicht", lambda: client.get_body_composition(window_start.isoformat(), today.isoformat()))
    for ds, weight in extract_weights(body).items():
        days.setdefault(ds, {"date": ds}).update(weight)

    known_acts = {a["id"]: a for a in previous.get("activities") or [] if a.get("id") is not None}
    since = window_start if (full or not known_acts) else today - timedelta(days=GARMIN_ACTIVITY_DAYS)
    raw_acts = safe("activiteiten", lambda: client.get_activities_by_date(since.isoformat(), today.isoformat()))
    if raw_acts is not None:
        since_text = since.isoformat()
        known_acts = {k: v for k, v in known_acts.items() if (v.get("start") or "")[:10] < since_text}
        for raw in raw_acts:
            activity = compact_garmin_activity(raw)
            if activity.get("id") is not None:
                known_acts[activity["id"]] = activity

    status = extract_status(
        safe("trainingsstatus", lambda: client.get_training_status(today.isoformat())),
        safe("vo2max", lambda: client.get_max_metrics(today.isoformat())),
        today.isoformat(),
    )

    try:
        state["garmin"] = {"seed": seed, "tokens": client.client.dumps()}
    except Exception as err:  # noqa: BLE001
        warnings.add("tokens", f"Nieuwe Garmin-tokens niet bewaard: {short_error(err)}")

    cutoff = window_start.isoformat()
    out = {
        "days": sorted((d for d in days.values() if d["date"] >= cutoff), key=lambda d: d["date"]),
        "activities": sorted(
            (a for a in known_acts.values() if (a.get("start") or "")[:10] >= cutoff),
            key=lambda a: a.get("start") or "",
            reverse=True,
        ),
        "status": status,
    }
    with_data = sum(1 for d in out["days"] if len(d) > 1)
    log(f"Garmin: {fetched} dagen opgehaald, {with_data} dagen met gegevens, {len(out['activities'])} activiteiten.")
    return out, warnings.items


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------


def load_previous(directory, key):
    found = {"bundle": {}, "state": {}}
    if not directory:
        return found["bundle"], found["state"]
    for name, slot in ((BUNDLE_FILE, "bundle"), (STATE_FILE, "state")):
        path = Path(directory) / name
        if not path.exists():
            continue
        try:
            found[slot] = decrypt_json(json.loads(path.read_text(encoding="utf-8")), key)
        except (CryptoError, ValueError) as err:
            log(
                f"::warning::Vorige {name} niet te lezen ({err}). Er wordt opnieuw begonnen; "
                "dat is normaal als je de synchronisatiesleutel hebt veranderd."
            )
    if found["bundle"].get("version") != BUNDLE_VERSION:
        found["bundle"] = {}
    return found["bundle"], found["state"]


def history_days_from(env):
    try:
        days = int(env.get("COACH_HISTORY_DAYS") or DEFAULT_HISTORY_DAYS)
    except ValueError:
        days = DEFAULT_HISTORY_DAYS
    return max(14, min(days, 730))


def run(args, env, *, now=None, today=None, session=None):
    try:
        key = normalise_key(env.get("COACH_SYNC_KEY"))
    except CryptoError as err:
        log(f"::error::{err} Zet hem als secret COACH_SYNC_KEY; dezelfde sleutel vul je in de app in.")
        return 1

    now = now or datetime.now(timezone.utc)
    today = today or date.today()
    history_days = history_days_from(env)
    previous, state = load_previous(args.previous, key)

    bundle = {
        "version": BUNDLE_VERSION,
        "generatedAt": iso_utc(now),
        "historyDays": history_days,
        "sources": {},
        "strava": previous.get("strava"),
        "garmin": previous.get("garmin"),
    }

    # Garmin's token file lives outside the output folder: that one is published.
    workdir = tempfile.mkdtemp(prefix="coach-sync-")
    jobs = (
        (
            "strava",
            strava_configured(env),
            lambda: sync_strava(session or http_session(), env, previous.get("strava"), state, today, history_days, args.full),
        ),
        (
            "garmin",
            garmin_configured(env),
            lambda: sync_garmin(env, previous.get("garmin"), state, today, history_days, args.full, workdir),
        ),
    )

    try:
        failures = run_jobs(jobs, bundle, previous)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    if args.dry_run:
        log(json.dumps(summarise(bundle), indent=2, ensure_ascii=False))
        return 2 if failures else 0

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / BUNDLE_FILE).write_text(json.dumps(encrypt_json(bundle, key, now=now)), encoding="utf-8")
    (out / STATE_FILE).write_text(json.dumps(encrypt_json(state, key, now=now)), encoding="utf-8")
    log(f"Versleuteld weggeschreven naar {out}.")
    return 2 if failures else 0


def run_jobs(jobs, bundle, previous):
    """Run each source on its own: one failing keeps its old data and status."""
    failures = 0
    for name, configured, job in jobs:
        before = (previous.get("sources") or {}).get(name) or {}
        if not configured:
            bundle["sources"][name] = {"configured": False}
            bundle[name] = None
            log(f"{name.capitalize()}: niet ingesteld, overgeslagen.")
            continue
        try:
            section, warnings = job()
        except Exception as err:  # noqa: BLE001 - one source failing must not lose the other
            failures += 1
            log(f"::error::{name.capitalize()}: {short_error(err)}")
            bundle["sources"][name] = {
                "configured": True,
                "ok": False,
                "error": short_error(err),
                "lastSuccess": before.get("lastSuccess"),
            }
            continue
        bundle[name] = section
        bundle["sources"][name] = {"configured": True, "ok": True, "lastSuccess": bundle["generatedAt"], "warnings": warnings}
        for warning in warnings:
            log(f"::warning::{name.capitalize()}: {warning}")
    return failures


def summarise(bundle):
    strava = bundle.get("strava") or {}
    garmin = bundle.get("garmin") or {}
    return {
        "generatedAt": bundle["generatedAt"],
        "sources": bundle["sources"],
        "stravaActivities": len(strava.get("activities") or []),
        "garminDays": len(garmin.get("days") or []),
        "garminActivities": len(garmin.get("activities") or []),
        "garminStatus": garmin.get("status"),
    }


def http_session():
    import requests

    session = requests.Session()
    session.headers["User-Agent"] = "coach-sync (+https://github.com)"
    return session


def main(argv=None):
    parser = argparse.ArgumentParser(description="Strava en Garmin ophalen voor de coach-app.")
    parser.add_argument("--previous", help="map met de vorige bundle.enc.json en state.enc.json")
    parser.add_argument("--out", default="coach-data", help="map waar de versleutelde bestanden komen")
    parser.add_argument("--full", action="store_true", help="alles in het venster opnieuw ophalen")
    parser.add_argument("--dry-run", action="store_true", help="niets wegschrijven, alleen een samenvatting tonen")
    return run(parser.parse_args(argv), os.environ)


if __name__ == "__main__":
    sys.exit(main())
