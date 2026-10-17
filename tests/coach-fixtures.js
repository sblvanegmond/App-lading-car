/**
 * A small but realistic decrypted bundle, shaped like coach/sync/sync.py
 * writes it: Saturday 17 October 2026, three days of Garmin data, a run that
 * exists in both Strava and Garmin, a Strava-only ride and a watch-only
 * strength session.
 */

export function sampleBundle() {
  return {
    version: 1,
    generatedAt: '2026-10-17T06:23:00Z',
    historyDays: 180,
    sources: {
      strava: { configured: true, ok: true, lastSuccess: '2026-10-17T06:23:00Z', warnings: [] },
      garmin: { configured: true, ok: true, lastSuccess: '2026-10-17T06:23:00Z', warnings: [] },
    },
    strava: {
      athlete: { id: 7, firstname: 'Sam', weightKg: 77.3, ftp: 250 },
      totals: { ytdRun: { count: 40, distanceM: 412000, movingS: 150000 } },
      activities: [
        {
          id: 101,
          name: 'Ochtendloop',
          sport: 'Run',
          start: '2026-10-16T07:30:00',
          startUtc: '2026-10-16T05:30:00Z',
          distanceM: 10012,
          movingS: 3012,
          elapsedS: 3100,
          elevationM: 35,
          avgSpeed: 3.324,
          avgHr: 151,
          maxHr: 172,
          calories: 690,
          detail: true,
          splits: [
            { km: 1, distanceM: 1000, movingS: 305, avgSpeed: 3.279, avgHr: 140 },
            { km: 2, distanceM: 1000, movingS: 300, avgSpeed: 3.333, avgHr: 150 },
          ],
        },
        {
          id: 102,
          name: 'Rondje Leekstermeer',
          sport: 'Ride',
          start: '2026-10-14T18:05:00',
          startUtc: '2026-10-14T16:05:00Z',
          distanceM: 45200,
          movingS: 5600,
          avgSpeed: 8.07,
          avgWatts: 182,
          avgHr: 138,
        },
      ],
    },
    garmin: {
      days: [
        {
          date: '2026-10-15',
          steps: 9100,
          rhr: 50,
          sleep: { totalS: 25200, deepS: 4800, remS: 5400, score: 78 },
          hrv: { lastNight: 58, status: 'BALANCED', baselineLow: 52, baselineHigh: 68 },
          bbHigh: 85,
        },
        {
          date: '2026-10-16',
          steps: 14200,
          rhr: 48,
          sleep: { totalS: 27000, score: 86 },
          hrv: { lastNight: 64, status: 'BALANCED' },
          bbHigh: 92,
          weightKg: 77.1,
        },
        { date: '2026-10-17', steps: 1200, rhr: 49, bbHigh: 88, readiness: { score: 72, level: 'HIGH' } },
      ],
      activities: [
        {
          id: 901,
          name: 'Grootegast Hardlopen',
          type: 'running',
          start: '2026-10-16T07:30:04',
          startUtc: '2026-10-16T05:30:04Z',
          durationS: 3100,
          distanceM: 10010,
          avgHr: 151,
          aerobicTE: 3.1,
          anaerobicTE: 0.8,
          trainingLoad: 95,
          teLabel: 'TEMPO',
        },
        {
          id: 902,
          name: 'Kracht',
          type: 'strength_training',
          start: '2026-10-15T19:00:00',
          startUtc: '2026-10-15T17:00:00Z',
          durationS: 2400,
          trainingLoad: 40,
          sets: 15,
        },
      ],
      status: { asOf: '2026-10-17', vo2max: 52.4, trainingStatus: 'PRODUCTIVE_1', acuteLoad: 612, chronicLoad: 540 },
    },
  };
}
