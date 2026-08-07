# DISC-vragenlijsten

Zelfstandige HTML-vragenlijsten voor het afnemen van DISC-assessments per functie.

## Bestanden

- `disc_questions.json` — brondata: 8 functies x 20 vragen x 4 forced-choice opties.
- `generate.js` — Node-script dat uit `disc_questions.json` de HTML-bestanden genereert (één per functie in het JSON-bestand) en daarbij de antwoordopties per vraag shuffelt.
- `disc_engineer.html`, `disc_projectleider.html`, `disc_assembly.html`, `disc_sales.html`, `disc_office.html`, `disc_hr.html`, `disc_directeur.html`, `disc_accountmanager.html` — de gegenereerde, volledig zelfstandige vragenlijsten (geen build-stap, geen externe dependencies, werken door het bestand direct in een browser te openen).

## Vragenlijst aanpassen / opnieuw genereren

1. Pas `disc_questions.json` aan (vragen, opties, DISC-codering, of voeg een nieuwe functie toe).
2. Genereer de HTML-bestanden opnieuw:

   ```
   node generate.js
   ```

   Dit overschrijft (of genereert nieuw) de `disc_<key>.html`-bestanden voor elke functie in `disc_questions.json`. De antwoordopties worden per vraag geshuffeld met een vaste, van de vraaginhoud afgeleide volgorde, zodat regenereren stabiel blijft zolang de brondata niet wijzigt, maar de DISC-lading niet meer overal in dezelfde A/B/C/D-volgorde staat.

## Gebruik door een medewerker

1. Open het HTML-bestand dat bij de functie van de medewerker hoort (bijv. `disc_engineer.html`) direct in een browser — dubbelklikken volstaat, er is geen server nodig.
2. Vul naam en datum in (functie staat al vast).
3. Beantwoord alle 20 vragen: kies per vraag de optie die **meest** van toepassing is en de optie die **minst** van toepassing is (dit moeten twee verschillende opties zijn).
4. Klik op **Opslaan**. Bij onvolledige of foutieve invoer worden de betreffende vragen gemarkeerd en toegelicht.
5. Bij een geldige invoer wordt automatisch een CSV-bestand gedownload, met bestandsnaam `disc_<functie>_<naam>_<datum>.csv`.

## CSV-structuur

Elke CSV bevat één respondent (header + 1 datarij), met kolommen:

```
Naam, Functie, Datum, Q1_meest, Q1_minst, Q2_meest, Q2_minst, ..., Q20_meest, Q20_minst, D_netto, I_netto, S_netto, C_netto
```

- `Qn_meest` / `Qn_minst`: de gekozen letter (A/B/C/D), niet de tekst.
- `D_netto`/`I_netto`/`S_netto`/`C_netto`: netto-score per DISC-as (+1 per keer als "meest" gekozen, -1 per keer als "minst" gekozen).

## Verzamelen en verwerken

Verzamel de gedownloade CSV-bestanden van alle medewerkers (bijv. via een gedeelde map of e-mail) en upload ze vervolgens in de tool waarin je de individuele DISC-profielrapportages genereert. Omdat elk databestand exact dezelfde kolomvolgorde heeft, kunnen de CSV's zonder verdere bewerking worden ingelezen.

---

# Break-even rekendashboard

`breakeven-dashboard.html` — een zelfstandige HTML-versie van het capaciteits- en break-evenrekenblad (`test_excel.xlsx`). Openen door het bestand direct in een browser te dubbelklikken; geen server, geen dependencies.

## Wat het doet

Alle variabele invoer staat op schuifregelaars; grafiek, kerncijfers, gevoeligheidsanalyse en scenariotabel bewegen live mee tijdens het verschuiven. Vaste kosten typ je in als bedrag. Elke schuifregelaar heeft daarnaast een invoerveld, zodat je ook exacte waarden kunt intypen.

- **Kerncijfer**: het aantal FTE bij het break-evenpunt, met de verdeling over consultancy en projecten.
- **Break-evengrafiek**: omzet en totale kosten als functie van het totaal aantal FTE. Op het snijpunt staat een label met omzetdoel, FTE in consultancy, FTE in projecten en FTE totaal.
- **Gevoeligheidsanalyse**: welke invoer het break-evenpunt (of het bedrijfsresultaat) het sterkst beweegt, gesorteerd op impact, per stap van één procentpunt / €5 / €5.000 afhankelijk van de invoer.
- **Scenariotabel**: dezelfde kolomopzet als het rekenblad, met de break-evenregel gemarkeerd.

## Rekenmodel

```
effectieve uren per FTE   = urenbasis × (1 − indirecte tijd%)
omzet per FTE             = gemiddeld tarief × effectieve uren
FTE per euro omzet        = omzetaandeel ÷ omzet per FTE
directe kosten per euro   = Σ afdelingen (FTE per euro × kosten per FTE)
variabele kosten per euro = directe kosten per euro × (1 + variabele overhead%)
break-even omzet          = vaste kosten ÷ (1 − variabele kosten per euro)
break-even FTE            = break-even omzet × FTE per euro omzet
```

De uitkomsten zijn afgestemd op het rekenblad: voor de uitgangswaarden geeft het dashboard voor elk omzetdoel uit de oorspronkelijke tabel (€2,0 mln t/m €5,5 mln) exact dezelfde FTE, totale kosten, resultaat en bedrijfsresultaat.

Twee bewuste afwijkingen van het rekenblad:

- De drie percentages van een senioriteitsmix blijven samen altijd 100%: verschuif je er één, dan schuiven de andere twee evenredig mee. In het rekenblad moest dat handmatig kloppen.
- Loopt de dekkingsbijdrage tot nul of lager (de omzet dekt de variabele kosten niet meer), dan is er geen break-evenpunt. Het dashboard meldt dat expliciet in plaats van een getal te tonen dat niets betekent.
