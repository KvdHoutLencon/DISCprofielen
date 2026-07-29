# Boldetectie in een samplingbeeld

Detecteert of er een **rond object** (bal/bol) aanwezig is in een klein camerabeeld
(~250×250 px), door dat samplingbeeld te vergelijken met een iets groter
referentiebeeld van dezelfde scene. Uitvoer: `"bal gedetecteerd"` of
`"geen bal gedetecteerd"`.

Geschreven als **pure Java 8, zonder enige dependency** — geen OpenCV, geen NDK, geen
Gradle-plugins. Kopieer `src/main/java/nl/spheredetect/` in je Android-project en het
werkt. Alles draait op één `float[]` per beeld.

---

## 1. Wat het aankan

| Eis | Hoe het wordt opgelost |
|---|---|
| Sampling mag **verschoven, gezoomd en geroteerd** zijn t.o.v. de referentie | 4-DOF uitlijning (schaal + rotatie + translatie), grof-naar-fijn over een beeldpiramide |
| **Belichting mag anders zijn** dan bij kalibratie | Uitlijning werkt op lokaal genormaliseerd contrast; het verschilbeeld corrigeert een vloeiend gain/offset-veld dat robuust per tegel wordt geschat |
| Object **ligt stil** | De uitlijning van het vorige beeld wordt hergebruikt → 3× sneller per frame |
| **Omtrek moet rond zijn** | Vier onafhankelijke rondheidstoetsen (zie §4), waaronder een harmonische vormanalyse die een vierkant van een bol onderscheidt |
| Object is mogelijk **klein (25×25 px)** | Contourpunten worden subpixel bepaald; de bekende maat perkt de zoekruimte in |
| Object kan **van achteren belicht** zijn → maanvormig | De detector heeft genoeg aan ~80° zichtbare contour i.p.v. 360° |
| **Verwachte grootte met marge** is vooraf bekend | Begrenst de radiuszoekruimte en is een harde toets achteraf |
| **Kalibratieprocedure** waarin belichting wordt ingeleerd | `Calibrator` leert ruisniveau, randsterkte, contrast, exacte radius en beslisdrempel |

### Testresultaat

`SyntheticSelfTest` bouwt een getextureerde scene, rendert daaruit samplingbeelden met
bekende verschuiving/zoom/rotatie, cameraruis en belichtingsverandering, en plaatst er
objecten in. **22 van de 22 gevallen correct**, met ruime marge tussen positief en
negatief (score positief 0,69–0,99; elk negatief afgekapt op 0,35 mét reden):

```
bal, zelfde opstelling                      -> BAL   score 0.88
bal, geschoven+geroteerd+gezoomd            -> BAL   score 0.99
bal, helderheid +25% en offset -18          -> BAL   score 0.97
bal r=9 / r=16 (binnen maatmarge)           -> BAL   score 0.81 / 0.91
tegenlicht: alleen maanvormige rand         -> BAL   score 0.72   (80 graden contour)
tegenlicht: maanvorm, andere hoek           -> BAL   score 0.69
bal, veel ruis (sigma 4)                    -> BAL   score 0.92
leeg beeld (4 varianten)                    -> leeg  score 0.00
vierkant 24x24 / balk 40x12 / driehoek      -> leeg  "hoekig" / "loopt buiten cirkel"
bal veel te klein (r=4) / te groot (r=28)   -> leeg  "buiten gekalibreerd bereik"
sterke schaduw over de hele scene           -> leeg
kleine bol r=6.5 en r=8 (2e kalibratie)     -> BAL   score 0.76 / 0.87
klein vierkant 13x13                        -> leeg
```

Teruggevonden radius vs echte radius: 11,74↔12 · 15,38↔16 · 8,62↔9 · 6,44↔6,5 ·
11,67↔12 (maanvorm) · 11,33↔13 (maanvorm).

Draai zelf: `javac -d build $(find src -name '*.java') && java -cp build nl.spheredetect.SyntheticSelfTest`

### Snelheid

Gemeten op de testmachine, 250×250 sampling tegen 320×320 referentie:

| | tijd |
|---|---|
| eerste beeld (volledige zoektocht naar de uitlijning) | **158 ms** |
| elk volgend beeld (uitlijning hergebruikt) | **44 ms** |

Op een telefoon ruwweg 2–3× zoveel. Draai het op een achtergrondthread; bij een
vaste camera geldt vrijwel altijd de tweede regel. Zie §7 om het sneller te maken.

---

## 2. Hoe het werkt

```
samplingbeeld ─┐
               ├─► 1. UITLIJNEN          schaal+rotatie+translatie t.o.v. referentie
referentie   ──┘                          robuuste kost: de bal zelf is een uitbijter
                  │
                  ▼
               2. VERSCHILBEELD          referentie warpen, belichtingsverschil
                                          wegregelen, residu = wat er bij gekomen is
                  │
                  ▼
               3. VERANDERMASKER         drempel op geleerd ruisniveau, vlekken groeperen
                  │
                  ▼
               4. CIRKELHYPOTHESEN       gradiëntrichting-stemming (cx, cy, r)
                  │
                  ▼
               5. CONTOUR VOLGEN         waaier van stralen, subpixel randpunten,
                                          radius via steunwaarde, robuuste cirkelfit
                  │
                  ▼
               6. RONDHEID TOETSEN       booglengte · radiusspreiding · harmonischen ·
                                          radialiteit · omsluiting · contrast
                  │
                  ▼
               "bal gedetecteerd" / "geen bal gedetecteerd" + score + (cx, cy, r)
```

De klassen volgen die stappen: `Registrar` → `ResidualMap` → `Blobs` →
`CircleHough` → `ContourTracer` → `RoundnessScorer`, aan elkaar geknoopt door
`SphereDetector`. Elk bestand legt in de klasse-javadoc uit *waarom* het doet wat het
doet; hieronder alleen de vier beslissingen die de zaak laten werken.

### Uitlijnen ondanks de bal (`Registrar`)

De bal staat wél in het samplingbeeld en niet in de referentie, dus is hij bij het
uitlijnen per definitie een blok uitbijters. Daarom is de kostfunctie **Lorentziaans**
in plaats van kwadratisch: de invloed van de bal verzadigt en de achtergrond bepaalt de
uitlijning. Beide beelden worden eerst **lokaal genormaliseerd** ((I−gemiddelde)/spreiding
over een klein venster), zodat een andere belichting de uitlijning niet raakt.

In de test wordt schaal 1,0104 · hoek 2,01° · verschuiving (3,96, −3,03) teruggevonden
waar (1,01 · 2,00° · (4, −3)) was opgelegd.

### Belichting wegregelen (`ResidualMap`)

Niet simpelweg aftrekken, maar `sample ≈ gain(x,y)·referentie(x,y) + offset(x,y)` passen,
met een vloeiend veld dat **per tegel robuust (IRLS)** wordt geschat. Robuust, zodat de
bal niet in het belichtingsmodel wordt opgeslokt; en tegels van minstens 4× de
baldiameter, om dezelfde reden. In de test wordt gain 1,25 / offset −18 exact
teruggevonden en verdwijnt zelfs een sterke schuine schaduw over de scene zonder één
valse detectie.

### De maanvorm (`ContourTracer`)

Dit is het lastigste deel, en drie dingen bleken essentieel:

1. **Randen worden geaccepteerd ongeacht hun teken.** Een maanvormige rand loopt langs
   zijn eigen lengte van *lichter* dan de achtergrond naar *donkerder* dan de
   achtergrond, dus het teken van de randovergang klapt onderweg om. Eén teken eisen
   gooit de helft van een perfect ronde contour weg.
2. **De radius wordt gekozen op steun**, niet op sterkte: voor elke kandidaat-radius
   wordt geteld hoeveel stralen daar een rand zien. Een ronde contour concentreert zich
   op één radius; de schaduwterminator vlak erbinnen is een *ellips* en waaiert uit over
   vele radii, en verliest daarom.
3. **Meerdere hypothesen per centrum.** Een belichte bol heeft twee concentrische ronde
   kenmerken: zijn silhouet, en de ring waar zijn eigen helderheid het steilst afvalt.
   Welke van de twee wint wisselt met achtergrond en belichting. Kiezen zou de gemeten
   radius per frame 15–20% laten springen — precies de maatmarge die de gebruiker heeft
   opgegeven. Dus worden beide als hypothese aangeboden; bij kalibratie kiest **de door
   de gebruiker opgegeven maat** welke bedoeld wordt, bij detectie wint degene die
   slaagt.

### Rond of hoekig (`RoundnessScorer`)

Radiusspreiding alleen is niet genoeg: bij r≈9 px spreidt een echte bol ~0,07·r en een
vierkant ~0,09·r — niet te scheiden. Wat wél scheidt is de **structuur** van de afwijking.
De contourradius wordt ontbonden in hoekharmonischen: een vierkant stopt vrijwel al zijn
afwijking in één harmonische (k=4; een driehoek k=3), terwijl die van een cirkel
ongestructureerde meetruis is, dun uitgesmeerd over alle harmonischen. Bij r≈13 px meet
het vierkant uit de test 0,095 en een echte bol 0,036.

De 2e harmonische (ovaalheid) krijgt een ruimere grens dan de 3e en hoger (hoekigheid),
omdat een echte bol daar wél iets van laat zien: zijn eigen schaduwverloop verschuift de
schijnbare rand licht, met een periode van één à twee slagen per omwenteling. Alleen
echte hoeken produceren k≥3.

---

## 3. Gebruik

### Kalibreren (eenmalig, in de kalibratiescherm-flow)

```java
Calibrator.Input in = new Calibrator.Input();
in.reference   = referenceImage;        // lege scene, ruimer dan het samplingvenster
in.positive    = sampleWithBall;        // samplingvenster mét bal
in.negative    = sampleWithoutBall;     // optioneel maar sterk aanbevolen
in.userCx      = 128;                   // wat de gebruiker aanwees
in.userCy      = 116;
in.userRadius  = 13;
in.radiusMarginFraction     = 0.35;     // ±35% maatmarge
in.expectPartialIllumination = true;    // tegenlicht/maanvorm mogelijk

Calibrator.Output out = Calibrator.calibrate(in, new DetectorConfig());
if (!out.ok) { toon(out.message); return; }      // Nederlandse, uitlegbare foutmelding
prefs.edit().putString("cal", propsToString(out.calibration.toProperties())).apply();
```

`out.message` is Nederlands en direct toonbaar, bijvoorbeeld:

> kalibratie gelukt. Radius 11.8 px (toegestaan 7.6-15.9 px), contour 347 graden, bol is
> lichter dan de achtergrond, score bol 0.86 vs leeg 0.00, drempel 0.53.

### Detecteren (per frame)

```java
SphereDetector det = new SphereDetector(referenceImage, calibration, config);  // eenmalig

DetectionResult r = det.detect(sampleImage);
label.setText(r.label);                 // "bal gedetecteerd" / "geen bal gedetecteerd"
if (r.ballDetected) overlay.drawCircle(r.cx, r.cy, r.r);
```

Roep `det.resetTracking()` aan zodra de gebruiker de camera opnieuw richt.
`SphereDetector` is **niet thread-safe** (hij onthoudt de vorige uitlijning): één
instantie per analysethread.

### Beeld uit de camera halen

De Y-vlak van een `YUV_420_888` frame is al grijswaarde — geen kleurconversie nodig:

```java
// in ImageAnalysis.Analyzer
ImageProxy.PlaneProxy y = image.getPlanes()[0];
byte[] buf = new byte[y.getBuffer().remaining()];
y.getBuffer().get(buf);

GrayImage sample = GrayImage.fromLuma(buf, y.getRowStride(), y.getPixelStride(),
        cropX, cropY, 250, 250);        // het samplingvenster
```

Voor het referentiebeeld hetzelfde, maar met een ruimer venster (zie hieronder).
Uit een `Bitmap`: `GrayImage.fromArgb(pixels, w, h)`.

### Hoe groot moet de referentie zijn?

De referentie moet het samplingvenster kunnen blijven bevatten bij de maximale beweging:

```
referentiezijde  ≥  samplingzijde × maxZoom × (cos θ + sin θ)  +  2 × maxVerschuiving
```

Voor 250 px sampling, 8% zoom, 8° rotatie en 20 px verschuiving: 250 × 1,08 × 1,08 + 40
≈ **332 px**. Neem beide vensters **gecentreerd op hetzelfde punt** van de sensor.
Zet daarna `maxZoomFactor`, `maxRotationDeg` en `maxShiftPx` in `DetectorConfig` op wat
je opstelling echt kan doen — kleiner betekent sneller én minder valse detecties.

---

## 4. De rondheidstoetsen

Elke kandidaat moet ze allemaal passeren; de score zegt daarna hoe overtuigend hij is.
`DetectionResult.message` en `CircleScore.rejectReason` noemen in het Nederlands welke
toets een kandidaat velde — handig bij het afregelen.

| Toets | Wat het meet | Vangt |
|---|---|---|
| `radius` | binnen het gekalibreerde venster | verkeerde maat |
| `arcDeg` | langste aaneengesloten stuk contour | losse randen, hoeken |
| `radiusRmse` | spreiding van de contourradius | onregelmatige vlekken |
| `shapeHarmonic` | 3e–6e harmonische = hoekigheid | vierkant, driehoek |
| `ellipticity` | 2e harmonische = ovaalheid | uitgerekte vormen |
| `orientation` | staan de randgradiënten radiaal? | toevallige randen |
| `containment` | ligt de verandering binnen de cirkel? | grote belichtingsvlekken |
| `contrastRatio` | amplitude t.o.v. wat is ingeleerd | te zwakke verschillen |

---

## 5. Kalibratiescherm — voorgestelde flow

1. **Referentie** — "richt de camera op de lege scene" → knop → sla referentiebeeld op.
   Waarschuw dat de camera hierna niet meer verplaatst mag worden.
2. **Met bal** — "leg de bal op zijn plek" → knop → samplingbeeld.
3. **Aanwijzen** — toon dat beeld, laat de gebruiker een cirkel over de bol slepen
   (`userCx/Cy/Radius`), plus een schuif voor de maatmarge (standaard ±35%).
4. **Zonder bal** *(optioneel, één tik)* — "haal de bal weg" → knop. Dit scherpt de
   beslisdrempel duidelijk aan; zonder dit beeld leidt de kalibrator een leeg beeld af
   uit de referentie, wat minder waard is.
5. **Vinkje "object kan van achteren belicht zijn"** → `expectPartialIllumination`.
6. `Calibrator.calibrate(...)` en toon `out.message`. Bij `!out.ok` staat er in gewoon
   Nederlands wat er mis is; laat de betreffende stap overdoen.
7. Toon ter controle de teruggevonden cirkel (`out.fittedCx/Cy/Radius`) over het beeld —
   die kan bewust iets afwijken van wat de gebruiker sleepte, want hij is subpixel
   nagemeten.

Bewaren: `calibration.toProperties()` (of `toJson()` voor logging) in SharedPreferences.
Terughalen: `Calibration.fromProperties(...)`.

**Opnieuw kalibreren** zodra de camera is verplaatst, of als de scene blijvend anders is
gaan ogen. Het referentiebeeld hoort bij de kalibratie: bewaar ze samen.

---

## 6. Bestanden

| Bestand | Rol |
|---|---|
| `SphereDetector.java` | **hoofdingang** — regelt de hele keten |
| `Calibrator.java` | **kalibratieprocedure**, met Nederlandse meldingen |
| `Calibration.java` | wat er geleerd is + opslaan/laden |
| `DetectorConfig.java` | instelbare grenzen, alles met uitleg |
| `DetectionResult.java` | uitkomst: label, score, cirkel, diagnostiek |
| `Registrar.java` | uitlijning sampling ↔ referentie |
| `ResidualMap.java` | warpen, belichting wegregelen, verandermasker |
| `Blobs.java` | vlekken labelen en groeperen |
| `CircleHough.java` | cirkelhypothesen via gradiëntstemming |
| `ContourTracer.java` | contour volgen, subpixel, radius via steun |
| `CircleFit.java` | robuuste cirkelfit (Kåsa + IRLS) |
| `RoundnessScorer.java` | rondheidstoetsen en score |
| `CircleScore.java` | alle gemeten kenmerken van één kandidaat |
| `GrayImage.java` `ImageOps.java` `GradField.java` `Stats.java` `LinAlg.java` `SimilarityTransform.java` | basisgereedschap |
| `src/test/.../SyntheticSelfTest.java` | zelftest + benchmark, geen testframework nodig |

---

## 7. Afregelen

Alles zit in `DetectorConfig`, elk veld met uitleg erboven. De knoppen die er het
meest toe doen:

**Sneller maken**
- `maxZoomFactor`, `maxRotationDeg`, `maxShiftPx` zo klein als je opstelling toelaat —
  dit bepaalt de zoekruimte en daarmee vrijwel de hele kost van het eerste beeld.
- `hypothesesToRefine` (8) omlaag naar 4.
- `reuseLastTransform` aan laten staan (standaard) bij een vaste camera.

**Minder valse detecties**
- `expectPartialIllumination = false` bij kalibratie als de belichting altijd gelijk is.
  Een vrijwel volledige omtrek eisen is verreweg het effectiefst.
- `radiusMarginFraction` krap houden.
- `maxShapeHarmonic` (0,055) omlaag, `minContrastRatio` (0,30) omhoog.
- `kSigma` (4,5) omhoog als ruis vlekken oplevert.

**Minder gemiste ballen**
- `radiusMarginFraction` ruimer, `minArcDeg` (70) lager.
- `minContrastRatio` omlaag als de bal soms nauwelijks afsteekt.

---

## 8. Grenzen — eerlijk

- **Onder ~20 px diameter wordt vorm-onderscheid onbetrouwbaar.** Randposities hebben
  ongeveer een vaste absolute onzekerheid, dus relatief tot de radius wordt die groter
  naarmate het object kleiner is; de hoekigheidstoets wordt daarom automatisch ruimer
  bij kleine radius (`shapeHarmonicRadiusSlack`). Een bal van 13 px doorsnee van een
  vierkant van 13 px onderscheiden is tegen de grens van wat er in de pixels zit. De
  door jou genoemde 25×25 px zit ruim aan de goede kant.
- **Maanvorm + klein + weinig contrast tegelijk** is de zwakste combinatie: dan blijven
  er weinig onafhankelijke aanwijzingen over. Zet in dat geval de maatmarge krap.
- **Mislukt de uitlijning**, dan is het antwoord `REGISTRATION_FAILED` en niet "geen bal" —
  behandel dat in de app apart (vraag om opnieuw richten of herkalibreren). De
  referentievrije terugvalmodus (`allowReferenceFreeFallback`) staat bewust uit: zonder
  referentie vervalt de sterkste aanwijzing.
- **Perspectief** wordt niet gemodelleerd, alleen gelijkvormigheid. Bij een sterk
  gekantelde camera die ook nog verschuift, klopt de uitlijning aan de randen minder.
  Voor een vaste opstelling is dat geen bezwaar.
- De **teruggevonden radius** kan bij een sterk belichte bol iets binnen het
  meetkundige silhouet liggen (zie §2). De ja/nee-uitkomst heeft daar geen last van,
  maar gebruik `r` niet als precieze maatmeting.
- De cijfers hierboven komen uit **synthetische** scenes. De structuur van het probleem
  (uitbijters bij het uitlijnen, belichtingsdrift, gedeeltelijke contour) is realistisch
  nagebouwd, maar echte camera's voegen zaken toe die hier niet in zitten: rolling
  shutter, autofocus-ademen, JPEG-artefacten, automatische witbalans. Meet met echte
  beelden na en verwacht dat vooral `kSigma`, `minContrastRatio` en de maatmarge
  bijstelling nodig hebben.
