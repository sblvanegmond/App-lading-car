/**
 * Calendar export.
 *
 * A push notification from a web app is unreliable on iPhone, but a calendar
 * event with an alarm works on every phone. So the plan can be dropped into
 * the phone's own calendar, alarm included.
 */

function pad(n) {
  return String(n).padStart(2, '0');
}

/** iCalendar wants UTC stamps in basic format: 20260918T043000Z */
export function icsStamp(date) {
  const d = new Date(date);
  return (
    `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}` +
    `T${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())}Z`
  );
}

function escapeText(text) {
  return String(text)
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

/** RFC 5545 asks for lines of at most 75 octets. */
function foldLine(line) {
  if (line.length <= 73) return line;
  const parts = [];
  let rest = line;
  parts.push(rest.slice(0, 73));
  rest = rest.slice(73);
  while (rest.length > 72) {
    parts.push(` ${rest.slice(0, 72)}`);
    rest = rest.slice(72);
  }
  if (rest.length) parts.push(` ${rest}`);
  return parts.join('\r\n');
}

/**
 * Build an .ics file with one event per charging window.
 *
 * @param {object} plan output of planCharging()
 * @param {object} [options] { alarmMinutes }
 * @returns {string} iCalendar document
 */
export function buildIcs(plan, { alarmMinutes = 10 } = {}) {
  const now = new Date();
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Laadmoment//Laadplanner//NL',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
  ];

  (plan?.blocks ?? []).forEach((block, index) => {
    const kwh = block.kwh.toFixed(1).replace('.', ',');
    const cost = block.cost.toFixed(2).replace('.', ',');
    const solarPct = Math.round(block.solarSharePct);
    const description =
      `Ongeveer ${kwh} kWh voor ${cost} euro.\n` +
      `Aandeel eigen zon: ${solarPct}%.\n` +
      `Gemiddelde prijs: ${(block.avgPricePerKwh * 100).toFixed(1).replace('.', ',')} ct/kWh.\n` +
      'Gepland door Laadmoment.';

    lines.push(
      'BEGIN:VEVENT',
      `UID:${icsStamp(block.from)}-${index}@laadmoment`,
      `DTSTAMP:${icsStamp(now)}`,
      `DTSTART:${icsStamp(block.from)}`,
      `DTEND:${icsStamp(block.to)}`,
      foldLine(`SUMMARY:${escapeText(`Auto laden (${kwh} kWh, ${cost} euro)`)}`),
      foldLine(`DESCRIPTION:${escapeText(description)}`),
      'BEGIN:VALARM',
      `TRIGGER:-PT${Math.max(0, Math.round(alarmMinutes))}M`,
      'ACTION:DISPLAY',
      'DESCRIPTION:Stekker in de auto',
      'END:VALARM',
      'END:VEVENT',
    );
  });

  lines.push('END:VCALENDAR');
  return `${lines.join('\r\n')}\r\n`;
}

/** Hand the file to the phone so it can be opened by the calendar app. */
export function downloadIcs(plan, filename = 'laadmoment.ics') {
  const blob = new Blob([buildIcs(plan)], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}
