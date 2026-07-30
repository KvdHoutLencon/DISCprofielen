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
