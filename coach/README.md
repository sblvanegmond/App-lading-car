# Coach

Een leefstijlcoach op je telefoon, met Claude als brein. De coach kent je
trainingen uit **Strava**, de gegevens van je **Garmin-horloge** (slaap, HRV,
rusthartslag, Body Battery, stress, stappen, trainingsstatus) en, als je dat
wilt, je **eten en drinken**. Je stelt vragen in gewone taal en krijgt advies
op basis van je eigen cijfers:

- *"Geef me mijn dagadvies."*
- *"Waarom voelde mijn duurloop van dinsdag zo zwaar?"*
- *"Maak een trainingsweek die past bij mijn halve marathon in april."*
- *"Ik heb net een bord pasta bolognese gegeten, zet het in mijn log."*

Het is een **progressive web app**, net als Laadmoment in dezelfde repository:
statische bestanden op GitHub Pages, op je beginscherm gezet, zonder server
die je moet huren.

---

## Hoe het werkt

```
 Strava ─┐                                  ┌─ jouw telefoon ──────────────┐
         ├─ GitHub Action ── versleuteld ──▶│  Coach-app                   │
 Garmin ─┘  (elke 2 uur)     branch          │   ontsleutelt je gegevens    │
                             coach-data      │   voedingslog (op toestel)   │
                                             │          │                   │
                                             └──────────┼───────────────────┘
                                                        ▼
                                                 Claude (Anthropic)
```

Waarom zo, en niet alles op de telefoon:

- **Strava** laat het vernieuwen van de toegang alleen toe vanaf een server,
  met het geheime *client secret*. Dat hoort niet in een webpagina.
- **Garmin** heeft geen API voor particulieren. De synchronisatie gebruikt
  [python-garminconnect](https://github.com/cyberjunky/python-garminconnect),
  een community-bibliotheek die inlogt zoals de Garmin Connect-app. Dat kan
  alleen buiten de browser.

Een geplande GitHub Action (`.github/workflows/coach-sync.yml`) haalt daarom
beide op, voegt ze samen en zet het resultaat **versleuteld** (AES-256-GCM)
op de branch `coach-data`. De app op je telefoon haalt dat bestand op en
ontsleutelt het met jouw synchronisatiesleutel. Zonder die sleutel is het
bestand onleesbaar, ook al staat het in een openbare repository.

De coach zelf praat rechtstreeks vanaf je telefoon met Claude, met je eigen
API-sleutel. Claude haalt via tools op wat nodig is: activiteiten in een
periode, de details van één training, je slaap en herstel per dag, je
trainingsweken, je voedingslog. Je voedingslog en je gesprekken staan alleen
op je telefoon.

---

## Stap 1 — De app online zetten

De coach staat in de map `coach/` en wordt samen met Laadmoment door GitHub
Pages gepubliceerd. Twee dingen moeten op de **standaardbranch** van de
repository staan, want alleen daar publiceert Pages en alleen daar draaien
geplande workflows:

1. Voeg deze branch samen met je standaardbranch (via een pull request in
   GitHub, of laat Claude dat doen).
2. Na de publicatie staat de app op
   `https://<jouw-gebruikersnaam>.github.io/App-lading-car/coach/`

Staat Pages nog niet aan, volg dan eerst stap 1 van de README van
Laadmoment.

## Stap 2 — Een Claude API-sleutel

1. Maak een account op [console.anthropic.com](https://console.anthropic.com)
   en zet er wat tegoed op.
2. Ga naar **Settings → API keys → Create key** en kopieer de sleutel
   (`sk-ant-…`).
3. Zet bij **Settings → Limits** een maandlimiet, bijvoorbeeld € 10. Dan
   weet je zeker wat het je maximaal kost.

De sleutel vul je straks in de app in. Hij blijft op je telefoon en gaat
alleen naar Anthropic.

## Stap 3 — De synchronisatiesleutel

Deze sleutel versleutelt je Strava- en Garmin-gegevens. Hij moet op twee
plekken precies hetzelfde zijn: in GitHub en in de app.

1. Open de app, ga naar **Instellingen → Strava en Garmin** en tik op
   **Nieuwe sleutel**, daarna op **Kopieer**.
2. Ga in GitHub naar **Settings → Secrets and variables → Actions → New
   repository secret** en maak `COACH_SYNC_KEY` aan met die sleutel.

Liever een eigen wachtwoordzin? Kan, maar minstens 16 tekens, en liever
langer: het versleutelde bestand staat openbaar.

## Stap 4 — Strava koppelen

1. Ga naar [strava.com/settings/api](https://www.strava.com/settings/api) en
   maak een API-applicatie. Naam en website mag je zelf kiezen; zet
   **Authorization Callback Domain** op `localhost`.
2. Op je computer, in de map van het project:

   ```bash
   npm run coach:strava-login
   ```

   Vul de *Client ID* en het *Client Secret* in. Je browser opent Strava;
   laat alle vinkjes aan en klik op **Autoriseren**. Het script drukt daarna
   af wat je in GitHub moet zetten.
3. Maak de drie secrets aan: `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET` en
   `STRAVA_REFRESH_TOKEN`.

Waarom niet het token dat op de Strava-pagina staat? Dat heeft alleen het
recht `read`, en daarmee zie je je activiteiten niet.

## Stap 5 — Garmin koppelen

1. Op je computer (Python 3.12 of nieuwer):

   ```bash
   pip install garminconnect curl_cffi
   npm run coach:garmin-login
   ```

   Log in met je Garmin-account. Heb je tweestapsverificatie aan, dan vraagt
   het script om de code. Je wachtwoord wordt nergens bewaard.
2. Het script drukt één regel tekst af. Maak daarmee het secret
   `GARMIN_TOKENS` aan.

Die regel is een sleutel tot je Garmin-account: zet hem nergens anders neer.
De synchronisatie vernieuwt de tokens zelf en bewaart de nieuwste versie
versleuteld naast je gegevens, dus normaal hoef je dit maar één keer te doen.

Liever zonder dit script? Zet dan `GARMIN_EMAIL` en `GARMIN_PASSWORD` als
secrets. Dat werkt alleen als je **geen** tweestapsverificatie gebruikt.

## Stap 6 — De eerste synchronisatie

Ga naar **Actions → Coach synchroniseren → Run workflow** en zet *Alles in
het venster opnieuw ophalen* aan. Na een paar minuten staat er een branch
`coach-data`. Daarna draait de workflow vanzelf, ongeveer elke twee uur van
de vroege ochtend tot middernacht.

De eerste keer haalt hij een half jaar Strava op, en van Garmin de laatste
weken; bij elke volgende run vult hij twee weken Garmin-geschiedenis bij,
tot het half jaar compleet is. Wil je een ander venster, maak dan onder
**Settings → Secrets and variables → Actions → Variables** de variabele
`COACH_HISTORY_DAYS` aan (14 tot 730).

## Stap 7 — Op je telefoon

1. Open `https://<jouw-gebruikersnaam>.github.io/App-lading-car/coach/`.
2. Zet hem op je beginscherm (iPhone: Safari → deelknop → *Zet op
   beginscherm*; Android: menu → *App installeren*). Open hem daarna vanaf je
   beginscherm: zo bewaart je telefoon je gegevens het best.
3. Vul bij **Instellingen** je profiel, de Claude-sleutel en de
   synchronisatiesleutel in. GitHub-gebruiker en repository vult de app zelf
   in.
4. Tik bovenin op het ververs-icoon. Op **Vandaag** verschijnen je herstel,
   je beweging en je eten en drinken.

### Optioneel: zelf synchroniseren vanaf je telefoon

Net getraind en meteen de coach vragen hoe het ging? Met een GitHub-token
start de knop **Nu synchroniseren** de workflow meteen.

1. GitHub → je profielfoto → **Settings → Developer settings → Personal
   access tokens → Fine-grained tokens → Generate new token**.
2. *Repository access*: alleen deze repository. *Permissions*: **Actions:
   Read and write** en **Contents: Read-only**.
3. Plak het token in de app bij *GitHub-token*.

Het token heeft de app ook nodig als je de repository privé maakt.

---

## Wat de coach kan

| Tool | Wat Claude ermee ophaalt |
|---|---|
| Activiteiten | Trainingen in een periode, Strava en Garmin samengevoegd: afstand, tijd, tempo, hartslag, vermogen, hoogtemeters, trainingseffect en -belasting |
| Activiteit in detail | Kilometersplits, ronden, beste prestaties, je beschrijving |
| Slaap en herstel | Per dag: slaapduur en -fasen, slaapscore, HRV, rusthartslag, Body Battery, stress, stappen, trainingsgereedheid, gewicht |
| Trainingsweken | Per week tijd, afstand en belasting per sport, plus VO2max en trainingsstatus |
| Voedingslog | Wat je logde, met dagtotalen en je streefwaarden |
| In het log zetten | Alleen als je erom vraagt: "zet mijn lunch erin" |
| Onthouden | Blijvende feiten, zoals een blessure of je wedstrijddoel. Je ziet en wist ze bij Instellingen |

Bij het begin van elk gesprek krijgt de coach een korte samenvatting van je
laatste dagen; voor de rest gebruikt hij de tools. Zo blijft een vraag snel
en goedkoop, en kan hij toch een half jaar terugkijken.

Een activiteit die zowel in Strava als op je horloge staat, komt één keer
voor: de app koppelt ze op starttijd. Strava levert de naam, splits en
beschrijving, Garmin het trainingseffect en de belasting.

## Eten en drinken

Volledig optioneel. Op **Voeding** kun je:

- met één tik water, koffie, thee, bier of wijn toevoegen;
- in gewone taal opschrijven wat je at, met of zonder foto, en **Schat met
  AI** laten invullen hoeveel kcal, eiwit, koolhydraten, vet en vocht erin
  zit. Je kunt de getallen daarna aanpassen;
- of het de coach in het gesprek laten doen.

Leeg laten mag altijd: "boterham met kaas" zonder getallen is ook prima, de
coach kan er nog steeds iets mee.

## Kosten

Je betaalt Anthropic per gebruik. Een gewone vraag aan de coach met Claude
Opus 5.5 kost naar schatting enkele centen; een uitgebreide analyse met veel
opgehaalde data iets meer. De app hergebruikt het begin van een gesprek via
*prompt caching*, waardoor vervolgvragen goedkoper zijn. Wil je het
goedkoper, kies dan bij Instellingen **Claude Sonnet 5.5** of **Claude Haiku
5.5**. Zet in elk geval een maandlimiet in de console (stap 2).

GitHub Actions en GitHub Pages zijn gratis voor een openbare repository.

## Privacy en veiligheid

- Je **voedingslog, gesprekken en instellingen** staan alleen op je
  telefoon. Maak af en toe een back-up via Instellingen; daar zitten je
  sleutels niet in.
- Je **Strava- en Garmin-gegevens** staan versleuteld op de branch
  `coach-data`. Die branch wordt bij elke synchronisatie overschreven, er
  blijft geen oude geschiedenis staan.
- Wat je **aan de coach vraagt** gaat, met de gegevens die hij daarvoor
  ophaalt, naar Anthropic. Volgens de voorwaarden van Anthropic worden
  API-gesprekken standaard niet gebruikt om modellen te trainen; lees hun
  privacybeleid als je het zeker wilt weten.
- De coach is **geen arts**. Bij klachten zoals pijn op de borst, flauwvallen
  of een blessure die niet overgaat, verwijst hij je door, en dat advies
  verdient het om op te volgen.
- Strava en Garmin: dit is bedoeld voor **eigen gebruik**. Je koppelt je
  eigen Strava-applicatie aan je eigen account; de Garmin-koppeling is
  onofficieel en kan stoppen als Garmin iets verandert.

## Als het niet werkt

| Wat je ziet | Wat het meestal is |
|---|---|
| *Nog geen data gevonden op de branch coach-data* | De workflow heeft nog niet gedraaid, of de branch staat in een andere repository dan de app denkt. Kijk bij **Actions**; vul anders gebruiker en repository zelf in. |
| *De gegevens konden niet ontsleuteld worden* | De sleutel in de app is niet dezelfde als `COACH_SYNC_KEY`. Kopieer hem opnieuw. |
| *Strava gaf 400/401 bij het vernieuwen van het token* | Een van de drie Strava-secrets klopt niet, of je hebt de toegang in Strava ingetrokken. Draai `npm run coach:strava-login` opnieuw. |
| *Inloggen bij Garmin mislukt* | De tokens zijn verlopen of Garmin heeft iets veranderd. Draai `npm run coach:garmin-login` opnieuw en vervang `GARMIN_TOKENS`. Helpt dat niet, kijk dan of er een nieuwe versie van `garminconnect` is en pas `coach/sync/requirements.txt` aan. |
| *Claude weigert de API-sleutel* | Typfout, of de sleutel is ingetrokken. |
| *Claude kon het verzoek niet verwerken: … credit balance …* | Je tegoed bij Anthropic is op. |
| De workflow is rood maar er staat wel nieuwe data | Eén bron faalde, de andere niet. In het logboek staat welke en waarom; de app laat het ook zien. |
| Geplande runs stoppen na een paar maanden | GitHub zet geplande workflows uit in een repository waar 60 dagen niets gebeurt. Zet hem weer aan bij **Actions**. |

---

## Ontwikkelen

```bash
npm install                 # SDK-bundelaar en Claude-bibliotheek
npm test                    # alle JavaScript-tests, zonder netwerk
npm run test:sync           # Python-tests van de synchronisatie (pip install cryptography)
npm run coach:sdk           # bundelt de Claude-bibliotheek in coach/vendor/
npm start                   # lokale server, coach op http://localhost:8080/coach/
```

Een end-to-end test die de echte app in Chromium doorloopt, met nagebootste
antwoorden van GitHub en Claude:

```bash
npm install --no-save playwright
node coach/tools/smoke.mjs /tmp/coach    # maakt ook schermafbeeldingen
```

Lokaal draaien van de synchronisatie, zonder iets weg te schrijven:

```bash
pip install -r coach/sync/requirements.txt
COACH_SYNC_KEY=… STRAVA_CLIENT_ID=… STRAVA_CLIENT_SECRET=… STRAVA_REFRESH_TOKEN=… \
  python3 coach/sync/sync.py --dry-run
```

### Opbouw

| Bestand | Verantwoordelijk voor |
|---|---|
| `coach/sync/sync.py` | Strava en Garmin ophalen, samenvoegen, incrementeel bijwerken |
| `coach/sync/crypto_box.py` | Versleutelen van de bundel (spiegel van `js/crypto.js`) |
| `coach/js/crypto.js` | Ontsleutelen op de telefoon met WebCrypto |
| `coach/js/data.js` | De bundel ophalen, activiteiten samenvoegen, week- en herstelsamenvattingen |
| `coach/js/coach.js` | Systeemprompt, tools, de gesprekslus met Claude |
| `coach/js/claude.js` | De Claude-bibliotheek laden, fouten in gewone taal, maaltijden schatten |
| `coach/js/nutrition.js` | Het voedingslog |
| `coach/js/ui.js` | Alle weergave |
| `coach/js/app.js` | Bedrading: opslag, tabbladen, formulieren, chat |
| `coach/js/store.js` | Opslag in IndexedDB |
| `coach/tools/build-sdk.js` | Bundelt `@anthropic-ai/sdk` voor de browser |
| `coach/tools/strava-login.js` | Eenmalig Strava koppelen |
| `coach/tools/garmin_login.py` | Eenmalig inloggen bij Garmin |
| `coach/tools/smoke.mjs` | End-to-end test in Chromium |

Een paar keuzes die je in de code terugziet:

- **Het gesprek groeit alleen aan.** De systeemprompt en de tools liggen
  vast zodra een gesprek begint, en Claude's antwoorden worden precies zo
  bewaard als ze terugkomen. Dat houdt prompt caching werkzaam en laat
  Claude's redenering over meerdere beurten heen intact. De datum en tijd
  gaan daarom mee in elk bericht, niet in de systeemprompt.
- **Optionele API-functies met een vangnet.** Op Opus en Sonnet vraagt de
  app om een automatische terugval op een ander model als een
  veiligheidsfilter een vraag onterecht weigert, en om leesbare
  tussenstappen ("ik bekijk je slaap van deze week"). Weigert de API zo'n
  verzoek met een 400, dan probeert de app het één keer zonder.
- **Twee kopieën van één training worden er één.** Op starttijd, met drie
  minuten speling.
