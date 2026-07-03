#!/usr/bin/env node
/*
 * Genereert 7 zelfstandige HTML-vragenlijsten uit disc_questions.json.
 * Gebruik: node generate.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, 'disc_questions.json');
const OUT_DIR = __dirname;

const functions = JSON.parse(fs.readFileSync(SRC, 'utf8'));

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderQuestion(q) {
  const optionRows = q.options
    .map(
      (o) => `
              <label class="option-row" data-letter="${o.letter}">
                <span class="option-letter">${o.letter}</span>
                <span class="option-text">${escapeHtml(o.text)}</span>
                <span class="option-radios">
                  <label class="radio-choice">
                    <input type="radio" name="q${q.number}_meest" value="${o.letter}" data-role="meest">
                    <span>Meest</span>
                  </label>
                  <label class="radio-choice">
                    <input type="radio" name="q${q.number}_minst" value="${o.letter}" data-role="minst">
                    <span>Minst</span>
                  </label>
                </span>
              </label>`
    )
    .join('');

  return `
        <fieldset class="question" id="question-${q.number}" data-qnum="${q.number}">
          <legend><span class="qnum">Vraag ${q.number}</span><span class="qstem">${escapeHtml(q.stem)}</span></legend>
          <div class="option-list">${optionRows}
          </div>
          <p class="question-error" role="alert"></p>
        </fieldset>`;
}

function renderTemplate(fn) {
  const questionsHtml = fn.questions.map(renderQuestion).join('\n');
  const questionsDataJson = JSON.stringify(
    fn.questions.map((q) => ({
      number: q.number,
      options: q.options.map((o) => ({ letter: o.letter, disc: o.disc })),
    }))
  );

  return `<!DOCTYPE html>
<html lang="nl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DISC-vragenlijst — ${escapeHtml(fn.title)}</title>
<style>
  :root {
    --color-bg: #f4f6f8;
    --color-surface: #ffffff;
    --color-border: #d7dce1;
    --color-text: #1f2933;
    --color-muted: #5c6773;
    --color-primary: #1f4e5f;
    --color-primary-dark: #163a47;
    --color-accent: #2f855a;
    --color-error: #b3261e;
    --color-error-bg: #fdecea;
    --radius: 6px;
    font-size: 16px;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    font-family: "Segoe UI", Arial, Helvetica, sans-serif;
    background: var(--color-bg);
    color: var(--color-text);
    line-height: 1.5;
  }
  .page {
    max-width: 900px;
    margin: 0 auto;
    padding: 2rem 1.25rem 4rem;
  }
  header.app-header {
    background: var(--color-primary);
    color: #fff;
    padding: 1.5rem 1.25rem;
  }
  header.app-header .header-inner {
    max-width: 900px;
    margin: 0 auto;
  }
  header.app-header h1 {
    margin: 0 0 0.25rem;
    font-size: 1.4rem;
    font-weight: 600;
  }
  header.app-header p {
    margin: 0;
    color: #dce7ea;
    font-size: 0.92rem;
  }
  .progress-bar-wrap {
    position: sticky;
    top: 0;
    z-index: 10;
    background: var(--color-surface);
    border-bottom: 1px solid var(--color-border);
    padding: 0.75rem 1.25rem;
  }
  .progress-bar-inner {
    max-width: 900px;
    margin: 0 auto;
    display: flex;
    align-items: center;
    gap: 1rem;
  }
  .progress-track {
    flex: 1;
    height: 8px;
    background: #e3e8ec;
    border-radius: 999px;
    overflow: hidden;
  }
  .progress-fill {
    height: 100%;
    background: var(--color-accent);
    width: 0%;
    transition: width 0.2s ease;
  }
  .progress-label {
    font-size: 0.9rem;
    color: var(--color-muted);
    white-space: nowrap;
    min-width: 9.5em;
    text-align: right;
  }
  .card {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius);
    padding: 1.25rem 1.5rem;
    margin-bottom: 1.5rem;
  }
  .card h2 {
    margin-top: 0;
    font-size: 1.1rem;
    color: var(--color-primary-dark);
  }
  .meta-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 1rem;
  }
  @media (max-width: 640px) {
    .meta-grid { grid-template-columns: 1fr; }
  }
  .field label {
    display: block;
    font-weight: 600;
    font-size: 0.88rem;
    margin-bottom: 0.3rem;
    color: var(--color-muted);
  }
  .field input {
    width: 100%;
    padding: 0.5rem 0.6rem;
    border: 1px solid var(--color-border);
    border-radius: var(--radius);
    font-size: 0.95rem;
    background: #fff;
    color: var(--color-text);
  }
  .field input:disabled {
    background: #eef1f3;
    color: var(--color-muted);
  }
  fieldset.question {
    border: 1px solid var(--color-border);
    border-radius: var(--radius);
    padding: 1rem 1.25rem 1.1rem;
    margin: 0 0 1.25rem;
    background: var(--color-surface);
  }
  fieldset.question.incomplete {
    border-color: var(--color-error);
    box-shadow: 0 0 0 1px var(--color-error);
  }
  fieldset.question legend {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 0.5rem;
    padding: 0 0.4rem;
    font-weight: 600;
  }
  .qnum {
    color: var(--color-primary);
    font-size: 0.85rem;
    text-transform: uppercase;
    letter-spacing: 0.03em;
  }
  .qstem {
    color: var(--color-text);
    font-size: 1rem;
    font-weight: 600;
  }
  .option-list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    margin-top: 0.6rem;
  }
  .option-row {
    display: grid;
    grid-template-columns: 1.75rem 1fr auto;
    align-items: center;
    gap: 0.75rem;
    padding: 0.5rem 0.6rem;
    border: 1px solid var(--color-border);
    border-radius: var(--radius);
    background: #fafbfc;
  }
  @media (max-width: 640px) {
    .option-row {
      grid-template-columns: 1.5rem 1fr;
    }
    .option-radios { grid-column: 1 / -1; }
  }
  .option-letter {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 1.75rem;
    height: 1.75rem;
    border-radius: 50%;
    background: var(--color-primary);
    color: #fff;
    font-weight: 700;
    font-size: 0.85rem;
  }
  .option-text {
    font-size: 0.95rem;
  }
  .option-radios {
    display: flex;
    gap: 1rem;
  }
  .radio-choice {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    font-size: 0.82rem;
    color: var(--color-muted);
    white-space: nowrap;
    cursor: pointer;
  }
  .radio-choice input {
    cursor: pointer;
  }
  .question-error {
    display: none;
    margin: 0.6rem 0 0;
    color: var(--color-error);
    background: var(--color-error-bg);
    border-radius: var(--radius);
    padding: 0.4rem 0.6rem;
    font-size: 0.85rem;
  }
  fieldset.question.incomplete .question-error {
    display: block;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    gap: 1rem;
    margin-top: 1rem;
  }
  #save-btn {
    background: var(--color-primary);
    color: #fff;
    border: none;
    border-radius: var(--radius);
    padding: 0.75rem 1.75rem;
    font-size: 1rem;
    font-weight: 600;
    cursor: pointer;
  }
  #save-btn:hover {
    background: var(--color-primary-dark);
  }
  #global-error {
    display: none;
    background: var(--color-error-bg);
    color: var(--color-error);
    border: 1px solid var(--color-error);
    border-radius: var(--radius);
    padding: 0.85rem 1rem;
    margin-bottom: 1.25rem;
  }
  #global-error.visible { display: block; }
  #global-error ul {
    margin: 0.4rem 0 0;
    padding-left: 1.2rem;
  }
  #save-success {
    display: none;
    background: #e6f4ea;
    color: #1e4620;
    border: 1px solid var(--color-accent);
    border-radius: var(--radius);
    padding: 0.85rem 1rem;
    margin-bottom: 1.25rem;
  }
  #save-success.visible { display: block; }
  footer {
    text-align: center;
    color: var(--color-muted);
    font-size: 0.8rem;
    padding: 1.5rem 0 0;
  }
</style>
</head>
<body>
<header class="app-header">
  <div class="header-inner">
    <h1>DISC-vragenlijst — ${escapeHtml(fn.title)}</h1>
    <p>${escapeHtml(fn.intro)}</p>
  </div>
</header>

<div class="progress-bar-wrap">
  <div class="progress-bar-inner">
    <div class="progress-track"><div class="progress-fill" id="progress-fill"></div></div>
    <div class="progress-label" id="progress-label">0 van 20 ingevuld</div>
  </div>
</div>

<div class="page">
  <div class="card">
    <h2>Gegevens</h2>
    <div class="meta-grid">
      <div class="field">
        <label for="input-naam">Naam</label>
        <input type="text" id="input-naam" autocomplete="name" required>
      </div>
      <div class="field">
        <label for="input-functie">Functie</label>
        <input type="text" id="input-functie" value="${escapeHtml(fn.title)}" disabled>
      </div>
      <div class="field">
        <label for="input-datum">Datum</label>
        <input type="date" id="input-datum" required>
      </div>
    </div>
  </div>

  <div id="global-error" role="alert">
    <strong>Niet alle vragen zijn volledig ingevuld.</strong>
    <span id="global-error-detail"></span>
  </div>

  <div id="save-success" role="status">
    De vragenlijst is opgeslagen en het CSV-bestand is gedownload.
  </div>

  <form id="disc-form">
${questionsHtml}

    <div class="actions">
      <button type="submit" id="save-btn">Opslaan</button>
    </div>
  </form>

  <footer>DISC-vragenlijst &middot; ${escapeHtml(fn.title)}</footer>
</div>

<script>
(function () {
  'use strict';

  var FUNCTION_KEY = ${JSON.stringify(fn.key)};
  var FUNCTION_TITLE = ${JSON.stringify(fn.title)};
  var QUESTIONS = ${questionsDataJson};

  var form = document.getElementById('disc-form');
  var progressFill = document.getElementById('progress-fill');
  var progressLabel = document.getElementById('progress-label');
  var globalError = document.getElementById('global-error');
  var globalErrorDetail = document.getElementById('global-error-detail');
  var saveSuccess = document.getElementById('save-success');
  var dateInput = document.getElementById('input-datum');

  function todayIso() {
    var d = new Date();
    var mm = String(d.getMonth() + 1).padStart(2, '0');
    var dd = String(d.getDate()).padStart(2, '0');
    return d.getFullYear() + '-' + mm + '-' + dd;
  }
  dateInput.value = todayIso();

  function getAnswer(qnum) {
    var meest = form.querySelector('input[name="q' + qnum + '_meest"]:checked');
    var minst = form.querySelector('input[name="q' + qnum + '_minst"]:checked');
    return {
      meest: meest ? meest.value : null,
      minst: minst ? minst.value : null,
    };
  }

  function isQuestionComplete(qnum) {
    var a = getAnswer(qnum);
    return a.meest !== null && a.minst !== null && a.meest !== a.minst;
  }

  function updateProgress() {
    var completed = 0;
    QUESTIONS.forEach(function (q) {
      if (isQuestionComplete(q.number)) completed += 1;
    });
    var pct = Math.round((completed / QUESTIONS.length) * 100);
    progressFill.style.width = pct + '%';
    progressLabel.textContent = completed + ' van ' + QUESTIONS.length + ' ingevuld';
    return completed;
  }

  function clearQuestionState(qnum) {
    var fieldset = document.getElementById('question-' + qnum);
    fieldset.classList.remove('incomplete');
    fieldset.querySelector('.question-error').textContent = '';
  }

  function markQuestionIncomplete(qnum, message) {
    var fieldset = document.getElementById('question-' + qnum);
    fieldset.classList.add('incomplete');
    fieldset.querySelector('.question-error').textContent = message;
  }

  form.addEventListener('change', function (evt) {
    var target = evt.target;
    if (target && target.matches('input[type="radio"]')) {
      var fieldset = target.closest('fieldset.question');
      var qnum = fieldset.getAttribute('data-qnum');
      var a = getAnswer(qnum);
      if (a.meest !== null && a.minst !== null && a.meest === a.minst) {
        markQuestionIncomplete(qnum, '"Meest" en "Minst" mogen niet dezelfde optie zijn.');
      } else {
        clearQuestionState(qnum);
      }
      updateProgress();
    }
  });

  updateProgress();

  function csvEscape(value) {
    var str = String(value);
    if (/[",\\n]/.test(str)) {
      return '"' + str.replace(/"/g, '""') + '"';
    }
    return str;
  }

  function sanitizeForFilename(str) {
    return String(str)
      .normalize('NFKD')
      .replace(/[\\u0300-\\u036f]/g, '')
      .replace(/[^a-zA-Z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '') || 'onbekend';
  }

  form.addEventListener('submit', function (evt) {
    evt.preventDefault();
    globalError.classList.remove('visible');
    saveSuccess.classList.remove('visible');

    var naam = document.getElementById('input-naam').value.trim();
    var datum = document.getElementById('input-datum').value;
    var incompleteNums = [];

    QUESTIONS.forEach(function (q) {
      var a = getAnswer(q.number);
      if (a.meest === null || a.minst === null) {
        incompleteNums.push(q.number);
        markQuestionIncomplete(q.number, 'Kies zowel "Meest" als "Minst" van toepassing.');
      } else if (a.meest === a.minst) {
        incompleteNums.push(q.number);
        markQuestionIncomplete(q.number, '"Meest" en "Minst" mogen niet dezelfde optie zijn.');
      } else {
        clearQuestionState(q.number);
      }
    });

    if (!naam) {
      var naamField = document.getElementById('input-naam');
      naamField.focus();
    }

    if (!naam || incompleteNums.length > 0) {
      var messages = [];
      if (!naam) messages.push('Vul je naam in.');
      if (incompleteNums.length > 0) {
        messages.push('Vraag ' + incompleteNums.join(', ') + ' is niet (correct) ingevuld.');
      }
      globalErrorDetail.textContent = ' ' + messages.join(' ');
      globalError.classList.add('visible');
      globalError.scrollIntoView({ behavior: 'smooth', block: 'start' });
      if (incompleteNums.length > 0) {
        var firstFieldset = document.getElementById('question-' + incompleteNums[0]);
        window.setTimeout(function () {
          firstFieldset.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }, 300);
      }
      return;
    }

    var discNetto = { D: 0, I: 0, S: 0, C: 0 };
    var row = {};
    QUESTIONS.forEach(function (q) {
      var a = getAnswer(q.number);
      row['Q' + q.number + '_meest'] = a.meest;
      row['Q' + q.number + '_minst'] = a.minst;
      var meestOpt = q.options.filter(function (o) { return o.letter === a.meest; })[0];
      var minstOpt = q.options.filter(function (o) { return o.letter === a.minst; })[0];
      discNetto[meestOpt.disc] += 1;
      discNetto[minstOpt.disc] -= 1;
    });

    var headers = ['Naam', 'Functie', 'Datum'];
    QUESTIONS.forEach(function (q) {
      headers.push('Q' + q.number + '_meest');
      headers.push('Q' + q.number + '_minst');
    });
    headers.push('D_netto', 'I_netto', 'S_netto', 'C_netto');

    var values = [naam, FUNCTION_TITLE, datum];
    QUESTIONS.forEach(function (q) {
      values.push(row['Q' + q.number + '_meest']);
      values.push(row['Q' + q.number + '_minst']);
    });
    values.push(discNetto.D, discNetto.I, discNetto.S, discNetto.C);

    var csvContent = headers.map(csvEscape).join(',') + '\\r\\n' + values.map(csvEscape).join(',') + '\\r\\n';
    var blob = new Blob(['\\ufeff' + csvContent], { type: 'text/csv;charset=utf-8;' });
    var url = URL.createObjectURL(blob);
    var filename = 'disc_' + FUNCTION_KEY + '_' + sanitizeForFilename(naam) + '_' + datum + '.csv';
    var link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);

    saveSuccess.classList.add('visible');
    saveSuccess.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
})();
</script>
</body>
</html>
`;
}

functions.forEach((fn) => {
  const html = renderTemplate(fn);
  const outPath = path.join(OUT_DIR, `disc_${fn.key}.html`);
  fs.writeFileSync(outPath, html, 'utf8');
  console.log(`Geschreven: ${outPath}`);
});

console.log(`Klaar. ${functions.length} HTML-bestanden gegenereerd.`);
