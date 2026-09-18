# Laadmoment

Een app voor op je telefoon die je elke ochtend laat zien wanneer je je auto
het beste kunt laden. De app weegt drie dingen tegen elkaar af:

1. **De dynamische stroomprijs in Nederland** — de day-ahead uurprijzen, met
   jouw eigen opslag, energiebelasting en btw erbovenop.
2. **De zon boven Grootegast** — de verwachte straling per uur op precies jouw
   dakvlakken, plus de zonuren van de dag.
3. **De capaciteit van je zonnepanelen** — vermogen in kWp, hellingshoek,
   richting, omvormerbegrenzing en systeemrendement.

Het resultaat is een concreet laadvenster: *"14:00 – 16:00, 22 kWh, € 2,72,
19% uit eigen zon"*, met de kosten afgezet tegen meteen laden.

Het is een **progressive web app**: statische bestanden, geen account, geen
API-sleutel, geen server die je moet betalen. Je zet hem op je beginscherm en
hij gedraagt zich als een gewone app, ook zonder internet.

---

## Stap 1 — De app online zetten

De app moet op HTTPS staan, anders staat je telefoon niet toe dat je hem
installeert. GitHub Pages doet dat gratis, en er zit een workflow in die het
werk doet.

Pages moet één keer met de hand aangezet worden. Een workflow mag dat niet
zelf doen; zolang het uit staat stopt de publicatie met *Create Pages site
failed: Resource not accessible by integration*. Dat is dus geen fout in de
app.

1. Ga in je repository naar **Settings → Pages**.
2. Zet bij *Build and deployment* de bron op **GitHub Actions**.
3. Draai de publicatie opnieuw via **Actions → Publiceren op GitHub Pages →
   Run workflow**. Vanaf nu gaat elke push vanzelf.
4. Na een minuut of twee staat de app op
   `https://<jouw-gebruikersnaam>.github.io/App-lading-car/`.

De workflow publiceert vanaf de **standaardbranch** van je repository. Nu is
dat `claude/app-laadmomenten-optimizer-xw4032`, omdat dat de eerste branch
was. Wil je hem `main` noemen, hernoem hem dan onder **Settings → General →
Default branch**; de workflow volgt vanzelf mee.

Twee dingen die aan de standaardbranch vastzitten: Pages publiceert er
vanaf, en geplande workflows zoals de ochtendmelding draaien alleen daar.
Zet je werk dus op de branch die je als standaard hebt ingesteld.

Werkt de workflow niet, kijk dan onder **Actions** welke stap rood is. Bij
*Settings → Actions → General* moeten workflows toegestaan zijn.

## Stap 2 — Op je telefoon zetten

Open de link hierboven in de browser van je telefoon.

- **iPhone**: deelknop onderin → *Zet op beginscherm*. Gebruik Safari, want
  vanuit een andere browser werkt installeren op iOS niet.
- **Android**: menu rechtsboven → *App installeren* of *Toevoegen aan
  startscherm*.

Open de app daarna vanaf je beginscherm, niet vanuit de browser. Dat is op
iOS de voorwaarde voor meldingen.

## Stap 3 — Je eigen gegevens invullen

Ga in de app naar **Instellingen** en loop de kaarten langs. Alles blijft op
je telefoon staan.

| Veld | Waar je het vindt |
|---|---|
| **Accucapaciteit** | In de handleiding van je auto, de bruikbare capaciteit. |
| **Laadvermogen** | Wat je laadpaal én auto samen halen. Een auto met een eenfasige lader haalt geen 11 kW, ook niet aan een driefasige paal. |
| **Minimaal laadvermogen** | Onder deze grens weigert de auto te laden. 6 A op één fase is ongeveer 1,4 kW. |
| **Laadverlies** | Bij wisselstroom is 8 tot 12 procent normaal. |
| **Klaar om** | Het tijdstip waarop de auto moet kunnen rijden. |
| **kWp, hellingshoek, richting** | Per dakvlak. Heb je oost en west, voeg dan een tweede vlak toe met de knop *Vlak toevoegen*. |
| **Omvormer maximaal** | Het AC-vermogen van je omvormer. Laat leeg als hij niet begrenst. |
| **Basisverbruik huis** | Wat je huis gemiddeld trekt. Kijk in je energie-app. Dit gaat van de zonopbrengst af voordat de auto aan de beurt is. |
| **Tarieven** | Uit je energiecontract. De energiebelasting verandert elk jaar; controleer hem in januari. |
| **Saldering** | Zet aan zolang saldering voor jou geldt. Zie de uitleg verderop. |

## Stap 4 — Pushmeldingen elke ochtend

Een website mag zichzelf op een telefoon niet wakker maken. Er moet dus iets
anders op het juiste moment wakker zijn dat de melding verstuurt. Dat is hier
een geplande GitHub Action die het laadplan met precies dezelfde code
uitrekent en één zin naar je telefoon stuurt. Gratis, en je hoeft nergens een
server voor te huren.

### 4a. Sleutels maken

Op je computer, in de map van het project:

```bash
npm run vapid
```

Dat drukt twee sleutels af. De publieke gaat in de app, de private wordt een
GitHub-secret. **Zet de private sleutel nooit in een bestand dat je commit.**

### 4b. Secrets in GitHub zetten

Ga naar **Settings → Secrets and variables → Actions → New repository
secret** en maak deze drie aan:

| Naam | Waarde |
|---|---|
| `VAPID_PUBLIC_KEY` | De publieke sleutel uit `npm run vapid`. |
| `VAPID_PRIVATE_KEY` | De privésleutel uit `npm run vapid`. |
| `VAPID_SUBJECT` | `mailto:jouw@adres.nl`. Pushdiensten willen weten wie er stuurt. |

### 4c. Je telefoon aanmelden

1. Open de app **vanaf je beginscherm**.
2. Ga naar de kaart **Pushmelding instellen**.
3. Plak de publieke sleutel in het veld en tik op **Aanmelden**. Je telefoon
   vraagt om toestemming voor meldingen; zeg ja.
4. Er verschijnt een blok tekst. Tik op **Kopieer aanmelding**.
5. Maak in GitHub een vierde secret aan: `PUSH_SUBSCRIPTION`, met die tekst
   als waarde.

Wil je meerdere telefoons, plak dan de aanmeldingen als lijst:
`[{...},{...}]`.

### 4d. Je instellingen meegeven

De Action draait op GitHub en kent jouw auto en panelen niet, dus die moet je
één keer meegeven.

1. Tik in de app op **Kopieer instellingen** (kaart *Instellingen meenemen*).
2. Maak in je repository een bestand `notify-config.json` en plak het erin.
   In `notify-config.example.json` staat een voorbeeld.
3. Commit en push.

Zet `currentSocPct` op een waarde die voor jou normaal is aan het eind van
een dag, bijvoorbeeld 40. GitHub weet niet hoe vol je accu echt is; de app op
je telefoon rekent wel met de echte stand die je daar invult.

### 4e. Uitproberen

Ga naar **Actions → Ochtendmelding → Run workflow**. Laat *force* aan staan en
start hem. Binnen een minuut hoort je telefoon te piepen. In het logboek zie
je het volledige plan staan, ook als er iets misgaat.

### Hoe de planning werkt

De workflow draait elk uur tussen 03:05 en 07:05 UTC. `tools/notify.js`
verstuurt alleen in het uur ná de tijd die in `notify-config.json` staat
(`notifications.time`). Daardoor komt de melding zowel in zomertijd als in
wintertijd op het goede moment, terwijl cron zelf altijd op UTC loopt.

GitHub kan een geplande run een kwartier tot een uur uitstellen als het druk
is. Zet je tijd dus liever iets vroeger dan precies op het moment dat je de
deur uit wilt.

### Als er geen melding komt

| Wat je ziet | Wat het meestal is |
|---|---|
| In het logboek: *Geen VAPID-sleutels of aanmeldingen ingesteld* | Eén van de vier secrets ontbreekt of heet net anders. |
| In het logboek: *Buiten het venster* | De run viel niet in het uur na je ingestelde tijd. Normaal: van de vijf runs doet er één het werk. |
| *Aanmelding verlopen (410)* | Je telefoon heeft de aanmelding ingetrokken, bijvoorbeeld na het opnieuw installeren van de app. Meld je opnieuw aan en vervang `PUSH_SUBSCRIPTION`. |
| *Geweigerd (403)* | De publieke sleutel in de app hoort niet bij `VAPID_PRIVATE_KEY`. Meld je opnieuw aan met de juiste sleutel. |
| De app zegt *Zet de app eerst op je beginscherm* | Je opende hem in Safari in plaats van vanaf het beginscherm. |
| Niets in Actions te zien | Geplande workflows worden op een slapende repository uitgezet. Push iets, of start hem met de hand. |

### De andere twee manieren

Pushmeldingen zijn de mooiste weg, maar niet de enige.

- **Agenda-afspraak.** De knop *Zet in agenda* maakt een `.ics`-bestand met
  een afspraak per laadvenster en een alarm tien minuten vooraf. Werkt op elke
  telefoon, zonder GitHub, zonder sleutels.
- **Melding bij openen.** Zet de schakelaar *Melding bij openen* aan: de app
  toont het plan van die dag zodra je hem na het ingestelde tijdstip opent.

---

## De drie strategieën

| Strategie | Wat het doet |
|---|---|
| **Goedkoopst** | Kijkt alleen naar de stroomprijs en laadt op vol vermogen in de goedkoopste uren. |
| **Balans** *(standaard)* | Rekent per uur de echte prijs uit: het deel dat uit je eigen zon komt kost je alleen de gemiste terugleververgoeding, de rest kost de all-in prijs. |
| **Max zon** | Laadt op het overschot van je panelen en vult alleen bij uit het net als je je doel anders niet haalt. |

Belangrijk detail: **zolang de saldering voor jou geldt**, levert eigen zon
gebruiken niets extra's op, want een teruggeleverde kWh streept precies tegen
een gekochte weg. Zet die schakelaar dan aan bij *Tarieven*; de app geeft zon
dan geen voorrang meer boven een goedkoop nachtuur. Zodra saldering voor jou
wegvalt, zet je hem uit en vul je je terugleververgoeding in.

## Hoe de berekening werkt

**Benodigde energie.** `accu × (doel − huidig) / 100`, gedeeld door
`1 − laadverlies`, want een deel van wat je koopt komt niet in de accu terecht.

**Prijs per uur.** `(marktprijs + opslag + energiebelasting) × (1 + btw)`.
De marktprijs wordt exclusief btw opgehaald, zodat de opslagen uit jouw
contract erbovenop kunnen.

**Zonopbrengst per uur.** De weer-API levert de straling op het schuine vlak
(`global_tilted_irradiance`) voor jouw hellingshoek en richting. Daaruit volgt:

```
celtemperatuur = buitentemperatuur + straling / 800 × 25
temperatuurfactor = 1 + tempcoëfficiënt × (celtemperatuur − 25)
vermogen = straling / 1000 × kWp × systeemrendement × temperatuurfactor
```

De vlakken worden opgeteld en daarna begrensd door de omvormer. Wat je huis
zelf verbruikt gaat eraf; wat overblijft kan naar de auto.

**Keuze van de uren.** Elk uur binnen het venster krijgt een effectieve prijs
per kWh: het zonnedeel tegen de gemiste terugleververgoeding, de rest tegen de
all-in prijs. De uren gaan van goedkoop naar duur op een rij, de app pakt ze
tot de accu vol is en vult het laatste uur gedeeltelijk. Aaneengesloten uren
worden tot één laadvenster samengevoegd.

**De deadline.** Standaard telt de eerstvolgende keer dat de klok op *Klaar om*
staat. Is dat moment zo dichtbij dat de lading er niet meer in past, dan
schuift het plan naar de dag erna — anders zou je 's ochtends een plan van tien
minuten zien.

## Gegevensbronnen

| Bron | Waarvoor | Sleutel nodig |
|---|---|---|
| [EnergyZero](https://www.energyzero.nl) `api.energyzero.nl` | Nederlandse day-ahead uurprijzen | nee |
| [Open-Meteo](https://open-meteo.com) `api.open-meteo.com` | Straling op het dakvlak, temperatuur, bewolking, zonuren | nee |

De prijzen voor de volgende dag komen meestal rond 15:00 uur beschikbaar. Tot
die tijd plant de app met wat er is en zegt dat erbij.

Heb je je eigen prijsbron, bijvoorbeeld via Home Assistant, dan kun je die
invullen bij *Prijsbron → Eigen endpoint*. De app accepteert zowel het
EnergyZero-formaat als een simpele lijst van `{ start, price }` in €/kWh
exclusief btw, en vervangt `{from}` en `{till}` door ISO-tijdstempels.

## Ontwikkelen

```bash
npm test        # tests op de reken-, parseer- en meldingslogica, zonder netwerk
npm start       # lokale server op poort 8080
npm run icons   # genereert de app-iconen (gebeurt ook bij npm start)
npm run vapid   # maakt een VAPID-sleutelpaar voor pushmeldingen
npm run notify  # rekent het plan uit en drukt de melding af zonder te versturen
```

Een end-to-end test die de echte app in Chromium laadt met nagebootste
API-antwoorden:

```bash
npm install --no-save playwright
node tools/smoke.mjs /tmp/laadmoment     # maakt ook schermafbeeldingen
```

### Opbouw

| Bestand | Verantwoordelijk voor |
|---|---|
| `js/config.js` | Standaardinstellingen en opslag op het toestel |
| `js/api.js` | Ophalen en parsen van prijzen en weerdata, met cache |
| `js/solar.js` | Van straling naar kilowatts: het pv-model |
| `js/pricing.js` | Marktprijs naar all-in prijs, waarde van eigen zon |
| `js/planner.js` | De planner: welke uren, hoeveel kWh, wat kost het |
| `js/briefing.js` | De woorden van de ochtendmelding, gedeeld met de notifier |
| `js/push.js` | Aanmelden voor pushmeldingen in de browser |
| `js/ui.js` | Alle weergave, inclusief het uurdiagram |
| `js/app.js` | Bedrading: knoppen, formulier, verversen |
| `js/ics.js` | Agenda-export |
| `sw.js` | Offline gebruik, pushmeldingen, achtergrondsync |
| `tools/notify.js` | De ochtendmelding, draait in GitHub Actions |
| `tools/generate-vapid.js` | Maakt het sleutelpaar voor pushmeldingen |
| `tools/make-icons.js` | Tekent de app-iconen en schrijft ze als PNG weg |

De reken- en parseerlogica staat bewust los van de DOM, zodat het met
`node --test` te testen is zonder browser en zonder netwerk. Daardoor kan
`tools/notify.js` letterlijk dezelfde planner draaien als je telefoon.

De PNG-iconen staan niet in git: `tools/make-icons.js` tekent ze en schrijft
ze zelf weg, zonder externe bibliotheek. `npm start` en de publicatie-workflow
roepen dat script automatisch aan.

## Nauwkeurigheid

De opbrengstberekening is een model, geen meting. Schaduw van een boom of
schoorsteen, sneeuw, vuil en de exacte oriëntatie van je dak zitten er niet
in. Klopt de voorspelling structureel niet, stel dan het *systeemrendement*
bij: dat is de knop waarmee je het model op je eigen installatie afstemt.

## Licentie

MIT.
