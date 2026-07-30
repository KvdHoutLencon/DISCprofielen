# Opdracht: bouw de Android-app rond deze boldetectie

Je krijgt een **complete, werkende en geteste detectiemodule**. Die hoef je niet te
schrijven — je hoeft hem alleen in te bouwen. Onderaan dit document staat alle broncode.

## Wat de module doet

Bepaalt of er een **rond object** (bal/bol) in een klein camerabeeld (~250×250 px) ligt,
door dat samplingbeeld te vergelijken met een iets groter referentiebeeld van dezelfde
scene. Uitkomst: `"bal gedetecteerd"` of `"geen bal gedetecteerd"`.

Het samplingbeeld mag t.o.v. de referentie verschoven, gezoomd en geroteerd zijn, en
anders belicht. Het object mag van achteren belicht zijn, zodat alleen een maanvormige
rand zichtbaar is. Het object ligt stil.

## Wat je moet bouwen

1. **Camerascherm** met een vast samplingvenster (~250×250 px) en een ruimer
   referentievenster, beide gecentreerd op hetzelfde punt van de sensor.
2. **Kalibratieprocedure** (stappen staan hieronder) die een `Calibration` oplevert en
   die samen met het referentiebeeld bewaart.
3. **Detectielus** die per frame `detect(...)` aanroept op een achtergrondthread en het
   label toont.

## Randvoorwaarden

- **Java 8, geen dependencies.** Geen OpenCV, geen NDK, geen extra libraries. Alles
  draait op `float[]`. Kopieer het `nl/spheredetect/`-pakket ongewijzigd in je project.
- **Verander de algoritmiek niet.** De constanten in `DetectorConfig` zijn afgeregeld
  tegen een testsuite van 22 gevallen; elk veld heeft javadoc die uitlegt waarvoor het
  dient. Instellen mag, herschrijven niet.
- **`SphereDetector` is niet thread-safe** (hij onthoudt de vorige uitlijning). Eén
  instantie per analysethread. Maak hem één keer aan, niet per frame.
- **Blokkeer de UI-thread niet.** Zie de snelheidscijfers hieronder.

---

## Integratie

### Beeld uit de camera

Het Y-vlak van een `YUV_420_888` frame is al grijswaarde — geen kleurconversie nodig:

```java
// in ImageAnalysis.Analyzer
ImageProxy.PlaneProxy y = image.getPlanes()[0];
byte[] buf = new byte[y.getBuffer().remaining()];
y.getBuffer().get(buf);

GrayImage sample = GrayImage.fromLuma(buf, y.getRowStride(), y.getPixelStride(),
        cropX, cropY, 250, 250);
```

Uit een `Bitmap`: `GrayImage.fromArgb(pixels, w, h)`.

### Hoe groot moet het referentievenster zijn?

```
referentiezijde  ≥  samplingzijde × maxZoom × (cos θ + sin θ)  +  2 × maxVerschuiving
```

Voor 250 px sampling, 8% zoom, 8° rotatie, 20 px verschuiving: ≈ **332 px**.
Zet daarna `maxZoomFactor`, `maxRotationDeg` en `maxShiftPx` in `DetectorConfig` op wat
de opstelling écht kan doen — krapper is sneller én minder valse detecties.

### Kalibreren

```java
Calibrator.Input in = new Calibrator.Input();
in.reference   = referenceImage;        // lege scene, ruimer venster
in.positive    = sampleWithBall;        // samplingvenster mét bal
in.negative    = sampleWithoutBall;     // optioneel, sterk aanbevolen
in.userCx      = 128;                   // wat de gebruiker aanwees
in.userCy      = 116;
in.userRadius  = 13;
in.radiusMarginFraction      = 0.35;    // ±35% maatmarge
in.expectPartialIllumination = true;    // tegenlicht/maanvorm mogelijk

Calibrator.Output out = Calibrator.calibrate(in, new DetectorConfig());
if (!out.ok) { toon(out.message); return; }
// bewaren: out.calibration.toProperties()  (of toJson() voor logging)
```

`out.message` is Nederlands en direct toonbaar, bijvoorbeeld:

> kalibratie gelukt. Radius 11.8 px (toegestaan 7.6-15.9 px), contour 347 graden, bol is
> lichter dan de achtergrond, score bol 0.86 vs leeg 0.00, drempel 0.53.

Bij `!out.ok` staat er in gewoon Nederlands wat er mis is; laat die stap overdoen.

### Detecteren

```java
SphereDetector det = new SphereDetector(referenceImage, calibration, config);  // eenmalig

DetectionResult r = det.detect(sampleImage);
label.setText(r.label);                 // "bal gedetecteerd" / "geen bal gedetecteerd"
if (r.ballDetected) overlay.drawCircle(r.cx, r.cy, r.r);
```

Roep `det.resetTracking()` aan zodra de gebruiker de camera opnieuw richt.

**Behandel `REGISTRATION_FAILED` apart.** Dat betekent "kon sampling niet op de
referentie leggen", níet "geen bal" — vraag de gebruiker opnieuw te richten of te
herkalibreren. Verwar de twee niet in de UI.

### Kalibratiescherm — voorgestelde flow

1. **Referentie** — "richt de camera op de lege scene" → knop → referentiebeeld.
   Waarschuw dat de camera hierna niet meer verplaatst mag worden.
2. **Met bal** — "leg de bal op zijn plek" → knop → samplingbeeld.
3. **Aanwijzen** — toon dat beeld, laat de gebruiker een cirkel over de bol slepen
   (`userCx/Cy/Radius`), plus een schuif voor de maatmarge (standaard ±35%).
4. **Zonder bal** *(optioneel, één tik)* — "haal de bal weg" → knop. Dit scherpt de
   beslisdrempel duidelijk aan.
5. **Vinkje "object kan van achteren belicht zijn"** → `expectPartialIllumination`.
6. `Calibrator.calibrate(...)`, toon `out.message`.
7. Toon ter controle de teruggevonden cirkel (`out.fittedCx/Cy/Radius`) over het beeld.
   Die wijkt bewust iets af van wat de gebruiker sleepte: hij is subpixel nagemeten.

Bewaren: `calibration.toProperties()` in SharedPreferences, terug via
`Calibration.fromProperties(...)`. **Het referentiebeeld hoort bij de kalibratie —
bewaar ze samen.** Opnieuw kalibreren zodra de camera verplaatst is.

---

## Snelheid

Gemeten op een ontwikkelmachine, 250×250 sampling tegen 320×320 referentie:

| | tijd |
|---|---|
| eerste beeld (volledige zoektocht naar de uitlijning) | **158 ms** |
| elk volgend beeld (uitlijning hergebruikt) | **44 ms** |

Op een telefoon ruwweg 2–3× zoveel. Achtergrondthread verplicht. Bij een vaste camera
geldt vrijwel altijd de tweede regel, want `reuseLastTransform` staat aan.

## Testresultaat

`SyntheticSelfTest` (meegeleverd, geen testframework nodig) draait 22 gevallen en die
slagen allemaal: positieven scoren 0,69–0,99 tegen drempel 0,53, elk negatief wordt
afgekapt op 0,35 mét reden. Gedekt: verschuiving/zoom/rotatie, helderheid +25% met
offset, ruis σ=4, maanvormige tegenlichtranden, vierkanten/balken/driehoeken, te grote
en te kleine ballen, en een sterke schaduw over de hele scene.

Draaien:

```
javac -d build $(find src -name '*.java') && java -cp build nl.spheredetect.SyntheticSelfTest
```

**Draai deze test één keer voordat je begint** — dan weet je dat je kopie compleet is.
Laat hem daarna staan als regressietest.

---

## Grenzen — eerlijk, niet wegpoetsen in de UI

- **Onder ~20 px doorsnee wordt vorm-onderscheid onbetrouwbaar.** Randposities hebben
  ongeveer een vaste absolute onzekerheid, dus relatief tot de radius wordt die groter
  naarmate het object kleiner is. Een bal van 13 px van een vierkant van 13 px
  onderscheiden zit tegen de grens van wat er in de pixels zit. 25×25 px zit ruim aan
  de goede kant.
- **Maanvorm + klein + weinig contrast tegelijk** is de zwakste combinatie. Zet dan de
  maatmarge krap.
- **`expectPartialIllumination = true` kost marge tegen valse detecties.** Zet hem uit
  als de belichting altijd gelijk is aan die bij kalibratie; een vrijwel volledige
  omtrek eisen is verreweg het effectiefst tegen valse detecties.
- **Perspectief wordt niet gemodelleerd**, alleen gelijkvormigheid (schaal + rotatie +
  translatie). Voor een vaste opstelling geen bezwaar.
- **`r` is geen precieze maatmeting.** Bij een sterk belichte bol kan de teruggevonden
  radius iets binnen het meetkundige silhouet liggen. De ja/nee-uitkomst heeft daar
  geen last van.
- **De cijfers komen uit synthetische scenes.** De structuur van het probleem is
  realistisch nagebouwd, maar echte camera's voegen rolling shutter, autofocus-ademen,
  JPEG-artefacten en automatische witbalans toe. Meet na met echte beelden; verwacht dat
  vooral `kSigma`, `minContrastRatio` en de maatmarge bijstelling nodig hebben. Bouw
  daarom een debugscherm dat `DetectionResult.toString()` en `result.candidates` toont —
  dat maakt afregelen in het veld een kwestie van aflezen.

## Afregelen

Alles zit in `DetectorConfig`, elk veld met uitleg erboven.

- **Sneller**: `maxZoomFactor` / `maxRotationDeg` / `maxShiftPx` krap; `hypothesesToRefine` 8→4.
- **Minder vals-positief**: `expectPartialIllumination=false`, maatmarge krap,
  `maxShapeHarmonic` omlaag, `minContrastRatio` omhoog, `kSigma` omhoog.
- **Minder gemist**: maatmarge ruimer, `minArcDeg` lager, `minContrastRatio` omlaag.

`DetectionResult.message` en `CircleScore.rejectReason` zeggen in het Nederlands wélke
toets een kandidaat velde. Gebruik dat bij het afregelen in plaats van gokken.

---

# Toevoeging: camerastream, launch-trigger en de kleurvraag

## Wat er bij is gekomen

Drie klassen bovenop de frame-detector. De frame-detector zelf is ongewijzigd.

| Klasse | Rol |
|---|---|
| `BallTracker` | **de nieuwe hoofdingang voor een stream.** Toestandsmachine met de "gelanceerd"-trigger |
| `LockedBallVerifier` | goedkope controle per frame op de vastgelegde balpositie |
| `ChromaPlanes` + `ColorModel` | optionele kleurbevestiging uit de U/V-vlakken |

Gebruik per frame:

```java
BallTracker tracker = new BallTracker(reference, calibration, cfg, new BallTracker.Config()); // eenmalig

BallTracker.Update u = tracker.update(sample, chroma, System.currentTimeMillis());
label.setText(u.label);          // "geen bal gedetecteerd" / "bal gedetecteerd" / "gelanceerd"
if (u.launched) onLaunch();      // exact één frame: gebruik deze flank, niet de toestand
```

`chroma` mag `null` zijn; dan draait alles op helderheid.

## De toestandsmachine

```
SEARCHING ──bal gevonden──► CONFIRMING ──3 s stabiel──► ARMED
    ▲                            │                        │
    │                       bal weg                  bal niet meer zichtbaar
    │                            ▼                        ▼
    └────────────────────────────┴──────────────── INTERRUPTED
    ▲                                               │           │
    │                                     bal terug │           │ achtergrond terug
    │                                               ▼           ▼
    └────────── na launchHoldMs ──────────────── ARMED      LAUNCHED
```

`CONFIRMING`, `ARMED` én `INTERRUPTED` melden allemaal **"bal gedetecteerd"**. Dat is de
kern van wat gevraagd werd: een korte onderbreking mag het label niet laten flikkeren.

## Waarom een korte onderbreking geen launch is

Dit is het lastigste punt en waar de meeste logica in zit. Een club die over de bal gaat
verbergt hem **net zo volledig** als een slag, en een oefenswing is niet langzamer dan een
echte. Wachten en kijken of de bal terugkomt is onvermijdelijk, maar tijd alleen is een
slechte test.

Wat het wél beslist is **wat er achterblijft** op de plek:

- **afgedekt door een club** → bal weg *en* er zit iets vreemds;
- **echt gelanceerd** → bal weg *en* het gras dat de referentie al kent is terug.

Een launch is dus niet "de bal wordt niet meer gedetecteerd", maar "de bal wordt niet meer
gedetecteerd **en** de achtergrond is terug". Daarnaast moet het licht rustig zijn: zolang
de belichtingscorrectie een schaduwrand aan het najagen is, verklaart hij de plek deels weg
en is "achtergrond terug" niet te vertrouwen.

Blijft er iets liggen (een schoen, een bag), dan geeft de tracker het na
`maxInterruptionMs` op en gaat terug naar zoeken — **zonder** trigger.

## Waarom de club verder niet stoort

Zodra de bal vastligt is de vraag niet meer "waar is een bal?" maar "ligt hij er nog?".
Alleen een klein venster rond de bekende positie wordt bekeken (~2,6 × radius). Een club
die ter addressering wordt neergezet, een schaduw die over de mat trekt, de voeten van de
speler — die vallen buiten wat er gemeten wordt. Dat is geen filter dat je kunt overtuigen,
het wordt simpelweg niet bekeken.

Dat maakt het ook goedkoop: **6,6 ms per frame** in ARMED tegen 44 ms voor een volledige
zoektocht (ontwikkelmachine; op een Pixel 6a/7a ruwweg 2–3×, dus reken op 15–20 ms). De
uitlijning wordt niet elk frame opnieuw gedaan maar elke 8 frames — een statief drift
langzaam.

## Timing

De trigger komt `launchConfirmFrames` (3) frames ná het daadwerkelijke vertrek, bij 60 fps
zo'n 50 ms. Die vertraging is de prijs voor niet-afgaan op elke oefenswing. **Houd daarom
een doorlopende videobuffer aan** en start niet met opnemen op de trigger.

Let ook op `armAfterMs = 3000`: een slag binnen 3 seconden na plaatsing geeft géén
trigger. Op een driving range waar in hoog tempo geslagen wordt is dat mogelijk te lang —
het is instelbaar.

## De kleurvraag: is zwart-wit de juiste weergave?

Kort antwoord: **ja, helderheid als basis, kleur als bevestiging — niet omgekeerd.**

Waarom helderheid de basis blijft:

- Een witte of gele bal heeft prima luma-contrast met gras. Luma is
  `0,299R + 0,587G + 0,114B`, dus een gele bal (R en G hoog) komt op ~89% van vol uit,
  een witte op 100%, tegen middengrijs voor gras.
- **Chroma is in 4:2:0 halve resolutie.** Een bal van 25 px is in de U/V-vlakken maar
  ~12 px. Alle geometrie die een bal van een clubkop onderscheidt — de ronde omtrek, de
  radius, de harmonischen — heeft juist volle resolutie nodig.
- Een kleurmasker alleen (wit-of-geel, niet-groen, genoeg verschil met de achtergrond)
  vindt élke wit/gele vlek van de juiste grootte: een witte schoen, een tee, de chromen
  clubface, een sticker op de mat. De rondheidstoetsen zijn wat die eruit gooien, en die
  werken op luma.

Waar kleur wél echt iets toevoegt, en waarvoor het hier is ingebouwd:

- **Schaduwen.** Dit is het sterkste argument en direct relevant voor een golfswing: de
  schaduw van de speler en van de club vegen over de detectiearea. Een harde schaduw
  verandert de helderheid enorm en de kleur nauwelijks. Voor de vraag "ziet deze plek er
  weer als gras uit?" — precies de vraag waar de launch-trigger op hangt — is chroma
  daardoor veel betrouwbaarder dan luma.
- Fel bezonnen gras is helder maar blijft groen, dus het kan zich niet als witte bal
  voordoen.
- Een gele bal op droog, bleek gras kan weinig helderheidscontrast hebben en veel
  kleurcontrast.

Zo is het geïmplementeerd: `ColorModel` leert bij kalibratie twee punten in het (U,V)-vlak
— de balkleur en de achtergrondkleur uit een ring om de bal — plus de afstand ertussen.
Die afstand beslist zelf of kleur bruikbaar is: bij een witte bal op beton of een
monochrome scene komt hij laag uit, `usable()` wordt false en de tracker draait stil verder
op helderheid alleen. Kleur mag het helderheidsoordeel **modereren, nooit overrulen**.

Praktisch: geef `ChromaPlanes` mee als je het hebt (het kost vrijwel niets, je wrapt de
ByteBuffers die de camera al gaf, geen Bitmap-conversie, geen allocatie per frame). Werkt
het zonder ook? Ja. Wordt het beter mét, buiten in de zon met bewegende schaduwen?
Vermoedelijk wel, en dat is precies waar de gele/witte-bal-op-groen-situatie zit.

Wat ik **niet** heb overgenomen uit het eerdere voorstel, en waarom: een binair
kleurmasker als *primaire* detectie (wit OF geel, EN niet-groen, dan grootste regio) laat
de beslissing hangen aan kleurdrempels op halve resolutie, en gooit de rondheidstoetsen
weg die nu juist de club, de schoen en de schaduw tegenhouden. De grootte/compactheid/
positie-checks uit dat voorstel zitten er al in, maar op de volle-resolutie luma-contour
en met een geleerde referentie erachter.

## Nieuwe instellingen

In `BallTracker.Config`:

| Veld | Standaard | Betekenis |
|---|---|---|
| `armAfterMs` | 3000 | hoe lang de bal stabiel moet liggen voor er getriggerd kan worden |
| `launchHoldMs` | 1000 | hoe lang "gelanceerd" blijft staan |
| `launchConfirmFrames` | 3 | frames "bal weg + achtergrond terug" voor de trigger |
| `maxInterruptionMs` | 1200 | hoe lang een onderbreking mag duren voor het opgeven |
| `absentFramesToInterrupt` | 2 | één gemist frame verandert nooit de toestand |
| `maxGainSlewForLaunch` | 0,012 | hoe rustig het licht moet zijn om "achtergrond terug" te vertrouwen |
| `presentThreshold` / `absentThreshold` | 0,50 / 0,34 | hysterese |
| `realignEveryNFrames` | 8 | hoe vaak de uitlijning wordt vernieuwd |

In `DetectorConfig`: `verifyWindowFactor`, `maxDriftFactor`, `maxGainStepPerFrame`,
`maxOffsetStepPerFrame`.

## Testresultaat stream

`SequenceSelfTest` speelt scenario's op 60 fps met continue statiefbeweging:

```
[OK] normale swing            bal geplaatst, addresseren, oefenswing over de bal,
                              addresseren, slag -> precies 1 trigger, 4 frames na de slag
[OK] alleen oefenswings       4x club over de bal -> 0 triggers, label blijft staan
[OK] schaduw over de bal      harde schaduwrand (45% donkerder) over de bal -> 0 triggers
[OK] slag voor het armen      slag na 1 s -> 0 triggers (bewust)
[OK] bal blijvend afgedekt    club blijft liggen -> 0 triggers, valt terug op zoeken
snelheid: 6,6 ms per frame in ARMED
```

15 van 15 checks. Draaien:
`javac -d build $(find src -name '*.java') && java -cp build nl.spheredetect.SequenceSelfTest`

## Grenzen van de streamlogica — eerlijk

- **Een getopte bal die binnen de detectiearea blijft liggen** geeft een trigger op de oude
  plek (de bal was immers geraakt) en armt daarna opnieuw op de nieuwe plek. Of dat gewenst
  is, is een productbeslissing.
- **Presence kan af en toe één frame wegvallen** terwijl de belichtingscorrectie zich
  herstelt van iets dat net langs kwam. Dat verandert de toestand niet
  (`absentFramesToInterrupt`) en is niet zichtbaar in het label, maar je ziet het wel in de
  debugcijfers. Niet verontrustend.
- **De schaduwtest is streng maar synthetisch.** Een echte harde schaduwrand die precies
  op het moment van de slag over de bal veegt, is de moeilijkste combinatie die er is: dan
  vallen "bal weg" en "licht beweegt" samen en wordt de trigger uitgesteld tot het licht
  rustig is. Dat is de veilige kant, maar het kan de trigger een paar frames vertragen.
- **De launch-trigger vereist ARMED.** Dat is zo gevraagd en het is ook wat spurieuze
  triggers tegenhoudt, maar het betekent dat de eerste bal na het opstarten pas na
  `armAfterMs` bruikbaar is.
- Meet met echte beelden na. Verwacht dat `armAfterMs`, `maxGainSlewForLaunch` en
  `backgroundThreshold` de velden zijn die je wilt bijstellen; bouw het debugscherm zodat
  `Update.toString()` en `LockedBallVerifier.Verdict` zichtbaar zijn.
