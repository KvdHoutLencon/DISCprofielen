# DISC-vragenlijsten

Zelfstandige HTML-vragenlijsten voor het afnemen van DISC-assessments per functie.

## Bestanden

- `disc_questions.json` — brondata: 7 functies x 20 vragen x 4 forced-choice opties.
- `generate.js` — Node-script dat uit `disc_questions.json` de 7 HTML-bestanden genereert.
- `disc_engineer.html`, `disc_projectleider.html`, `disc_assembly.html`, `disc_sales.html`, `disc_office.html`, `disc_hr.html`, `disc_directeur.html` — de gegenereerde, volledig zelfstandige vragenlijsten (geen build-stap, geen externe dependencies, werken door het bestand direct in een browser te openen).

## Vragenlijst aanpassen / opnieuw genereren

1. Pas `disc_questions.json` aan (vragen, opties, DISC-codering).
2. Genereer de HTML-bestanden opnieuw:

   ```
   node generate.js
   ```

   Dit overschrijft alle 7 `disc_<key>.html`-bestanden met de actuele inhoud van `disc_questions.json`.

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
