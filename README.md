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

## Wat het is

Een **progressive web app**: één map met statische bestanden, geen server,
geen account, geen API-sleutel. Je zet hem op je beginscherm en hij gedraagt
zich als een gewone app, ook zonder internet (dan met de laatst opgehaalde
gegevens).

## Op je telefoon zetten

1. Zet de app online (zie *Publiceren* hieronder) en open de link op je
   telefoon.
2. **iPhone**: deelknop → *Zet op beginscherm*.
   **Android**: menu → *App installeren*.
3. Open de app, ga naar **Instellingen** en vul je auto, je panelen en je
   tarieven in. Alles wordt alleen op je telefoon bewaard.

### Publiceren met GitHub Pages

De repository bevat een workflow die dat automatisch doet. Zet in GitHub bij
**Settings → Pages** de bron op *GitHub Actions*. Elke push naar de
hoofdbranch publiceert de app op `https://<gebruiker>.github.io/<repo>/`.

Wil je hem eerst lokaal bekijken:

```bash
npm start           # genereert de iconen en start http://localhost:8080
```

Op `localhost` werkt alles, inclusief de service worker. Om de app echt te
installeren heb je HTTPS nodig, en daarvoor is GitHub Pages de makkelijkste
weg.

## Elke ochtend een seintje

Een website mag zichzelf op een telefoon niet zomaar wakker maken. Daarom
biedt de app drie wegen, van betrouwbaar naar handig:

- **Agenda-afspraak** (werkt overal, ook op iPhone). De knop *Zet in agenda*
  maakt een `.ics`-bestand met een afspraak per laadvenster, inclusief alarm
  tien minuten vooraf. Je telefoon geeft dan gewoon een melding.
- **Melding bij openen**. Zet je *Ochtendmelding* aan, dan toont de app het
  plan van die dag zodra je hem na het ingestelde tijdstip opent.
- **Achtergrondmelding** (alleen Android/Chrome). Waar de browser periodieke
  achtergrondsynchronisatie ondersteunt, tikt de app je 's ochtends zelf aan.

Wil je het echt automatisch op een iPhone: maak in **Opdrachten** een
ochtendautomatisering die de app opent. Dan krijg je het plan elke dag te
zien zonder er zelf aan te denken.

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

## Instellingen die ertoe doen

- **Laadvermogen** — wat je laadpaal én auto samen halen. Een auto met een
  eenfasige lader haalt geen 11 kW, ook niet aan een driefasige paal.
- **Omvormer maximaal** — knipt de piek op zonnige dagen af. Laat leeg als je
  omvormer niet begrenst.
- **Basisverbruik huis** — gaat van de zonopbrengst af voordat de auto aan de
  beurt is. Kijk in je energie-app wat je huis gemiddeld trekt.
- **Energiebelasting** — verandert elk jaar. Controleer hem in januari.

## Ontwikkelen

```bash
npm test      # 53 tests op de reken- en parseerlogica, zonder netwerk
npm start     # lokale server op poort 8080
npm run icons # genereert de app-iconen (gebeurt ook bij npm start)
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
| `js/ui.js` | Alle weergave, inclusief het uurdiagram |
| `js/app.js` | Bedrading: knoppen, formulier, verversen |
| `js/ics.js` | Agenda-export |
| `sw.js` | Offline gebruik en achtergrondmelding |
| `tools/make-icons.js` | Tekent de app-iconen en schrijft ze als PNG weg |

De reken- en parseerlogica staat bewust los van de DOM, zodat het met
`node --test` te testen is zonder browser en zonder netwerk.

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
