#!/usr/bin/env python3
"""One-time structural setup for the Chinese Word <-> Hanzi sync.

See docs/chinese-word-hanzi-sync-plan.md for the full design. This talks to Anki
Desktop's AnkiConnect add-on (http://127.0.0.1:8765) rather than AnkiDroid or the app,
because AnkiDroid's ContentProvider API (AddContentApi) has no way to add a field or
edit a template on an *existing* note type -- only AnkiConnect, running against Anki
Desktop directly, can do that. Needs Anki Desktop open with AnkiConnect installed
(add-on code 2055492159).

What this does:
  1. Adds "Constituents" and "UsedInWords" fields to the existing Hanzi note type
     ("RSH" in Anki Desktop), appended at the end (confirmed live against this
     collection that modelFieldAdd with no index appends, doesn't insert at 0).
  2. Adds a recursive expand/collapse tree renderer for "Constituents" to that note
     type's card template + CSS.
  3. Creates the new "Chinese Word" note type (Word/Translation/Pinyin/Audio/
     HanziBreakdown) with its own card template.
  4. Creates the "Chinese Words" deck.

Every step checks whether its target already exists/matches before writing anything,
so this is safe to re-run.

The repeating Word <-> Hanzi sync itself (creating notes, filling in the breakdown/
reverse-link fields) is a separate thing -- it runs on-device from the app, not from
this script. This script only ever runs once (or again later if the schema needs to
change).

Usage: python3 scripts/setup_chinese_word_deck.py
"""
import json
import urllib.request

ANKICONNECT_URL = "http://127.0.0.1:8765"

HANZI_MODEL_NAME = "RSH"
NEW_HANZI_FIELDS = ["Constituents", "UsedInWords"]

WORD_MODEL_NAME = "Chinese Word"
WORD_FIELDS = ["Word", "Translation", "Pinyin", "Audio", "HanziBreakdown"]

WORD_DECK_NAME = "🇨🇳 Chinese Words"

# Also used as a "have we already made this edit" marker when re-running.
CONSTITUENT_TREE_MARKER = "constituent-tree-render"

CONSTITUENT_TREE_HTML = """
{{#Constituents}}
<div id="constituent-tree-data" style="display:none">{{Constituents}}</div>
<div class="constituent-tree-render" id="constituent-tree-render"></div>
<script>
(function() {
  var raw = document.getElementById('constituent-tree-data');
  var target = document.getElementById('constituent-tree-render');
  if (!raw || !target) return;
  var tree;
  try { tree = JSON.parse(raw.textContent); } catch (e) { return; }

  function renderNode(node) {
    var li = document.createElement('li');
    var row = document.createElement('span');
    row.className = 'ct-node';
    row.textContent = node.char + (node.op ? ' ' + node.op : '');
    li.appendChild(row);
    if (node.children && node.children.length) {
      li.classList.add('ct-collapsed');
      var ul = document.createElement('ul');
      node.children.forEach(function(child) { ul.appendChild(renderNode(child)); });
      li.appendChild(ul);
      row.classList.add('ct-expandable');
      row.addEventListener('click', function() { li.classList.toggle('ct-collapsed'); });
    }
    return li;
  }

  var rootUl = document.createElement('ul');
  rootUl.className = 'ct-root';
  rootUl.appendChild(renderNode(tree));
  target.appendChild(rootUl);
})();
</script>
{{/Constituents}}
"""

CONSTITUENT_TREE_CSS = """
.constituent-tree-render {
  text-align: left;
  max-width: 90%;
  margin: 16px auto 0;
  font-size: 18px;
}
.constituent-tree-render ul {
  list-style: none;
  padding-left: 20px;
  margin: 4px 0;
}
.constituent-tree-render ul.ct-root {
  padding-left: 0;
}
.constituent-tree-render .ct-expandable {
  cursor: pointer;
  font-weight: bold;
}
.constituent-tree-render .ct-collapsed > ul {
  display: none;
}

.nightMode .constituent-tree-render .ct-expandable,
.night_mode .constituent-tree-render .ct-expandable {
  color: #7fb3ff;
}
"""

WORD_FRONT_TEMPLATE = """
<div class="word-front">
  <div class="word">{{Word}}</div>
  {{#Audio}}<div class="audio">{{Audio}}</div>{{/Audio}}
</div>
"""

# {{Tags}} renders a note's raw tags verbatim (e.g. "HSK::1") -- Anki's mustache templates have
# no string manipulation of their own, so turning that into "HSK1" needs the same hidden-data +
# small script pattern already used for the Constituents tree: stash the raw value in a hidden
# div, then a tiny script strips "::" and writes the result into the visible one.
WORD_BACK_TEMPLATE = """
<div class="word-front">
  <div class="word">{{Word}}</div>
  {{#Audio}}<div class="audio">{{Audio}}</div>{{/Audio}}
</div>
<hr id="answer">
<div class="word-back">
  {{#Tags}}
  <div id="tags-data" style="display:none">{{Tags}}</div>
  <div class="tags-display" id="tags-display"></div>
  <script>
  (function() {
    var raw = document.getElementById('tags-data');
    var target = document.getElementById('tags-display');
    if (!raw || !target) return;
    target.textContent = raw.textContent.trim().split(' ').filter(Boolean)
      .map(function(t) { return t.replace(/::/g, ''); }).join(' ');
  })();
  </script>
  {{/Tags}}
  <div class="translation">{{Translation}}</div>
  <div class="pinyin">{{Pinyin}}</div>
  {{#HanziBreakdown}}<div class="hanzi-breakdown">{{HanziBreakdown}}</div>{{/HanziBreakdown}}
</div>
"""

# Migration markers, so ensure_word_answer_tags() can find-and-fix an already-live template
# (created before this shape existed) rather than re-deriving the diff by hand each time.
_FRONT_WITH_SMALL_TAGS = """<div class="word-front">
  <div class="word">{{Word}}</div>
  {{#Tags}}<div class="tags">{{Tags}}</div>{{/Tags}}
  {{#Audio}}<div class="audio">{{Audio}}</div>{{/Audio}}
</div>"""
_OLD_ANSWER_BLOCK = """<div class="word-back">
  <div class="translation">{{Translation}}</div>
  <div class="pinyin">{{Pinyin}}</div>
  {{#HanziBreakdown}}<div class="hanzi-breakdown">{{HanziBreakdown}}</div>{{/HanziBreakdown}}
</div>"""
_NEW_ANSWER_BLOCK = """<div class="word-back">
  {{#Tags}}
  <div id="tags-data" style="display:none">{{Tags}}</div>
  <div class="tags-display" id="tags-display"></div>
  <script>
  (function() {
    var raw = document.getElementById('tags-data');
    var target = document.getElementById('tags-display');
    if (!raw || !target) return;
    target.textContent = raw.textContent.trim().split(' ').filter(Boolean)
      .map(function(t) { return t.replace(/::/g, ''); }).join(' ');
  })();
  </script>
  {{/Tags}}
  <div class="translation">{{Translation}}</div>
  <div class="pinyin">{{Pinyin}}</div>
  {{#HanziBreakdown}}<div class="hanzi-breakdown">{{HanziBreakdown}}</div>{{/HanziBreakdown}}
</div>"""

WORD_CSS = """
.word-front, .word-back {
  font-family: Arial, sans-serif;
  padding: 20px;
  border: 1px solid #ccc;
  background-color: #f9f9f9;
  color: #222;
  text-align: center;
}
.word { font-size: 48px; margin-bottom: 16px; }
.tags-display { font-size: 24px; font-weight: bold; color: #555; margin-bottom: 12px; }
.translation { font-size: 23px; font-weight: bold; text-transform: capitalize; margin-bottom: 12px; }
.pinyin { font-style: italic; color: #1a4fd6; font-size: 18px; margin-bottom: 16px; }
.hanzi-breakdown { text-align: left; max-width: 90%; margin: 12px auto 0; font-size: 16px; }

.nightMode .word-front, .nightMode .word-back,
.night_mode .word-front, .night_mode .word-back {
  background-color: #2c2c2c;
  color: #f0f0f0;
  border-color: #555;
}
.nightMode .tags-display, .night_mode .tags-display { color: #ccc; }
.nightMode .pinyin, .night_mode .pinyin { color: #7fb3ff; }
"""

# Old (revision-1) CSS rules to strip out if present, since .tags no longer exists anywhere.
_OLD_TAGS_CSS_RULES = [
    '.tags { font-size: 12px; color: #888; margin-top: 4px; }\n',
    '.nightMode .tags, .night_mode .tags { color: #999; }\n',
]
_TAGS_DISPLAY_CSS_MARKER = ".tags-display {"


def invoke(action: str, **params):
    payload = json.dumps({"action": action, "version": 6, "params": params}).encode("utf-8")
    req = urllib.request.Request(ANKICONNECT_URL, data=payload)
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = json.loads(resp.read())
    if body.get("error") is not None:
        raise RuntimeError(f"{action} failed: {body['error']}")
    return body["result"]


def ensure_connected():
    version = invoke("version")
    print(f"Connected to AnkiConnect (API v{version})")


def ensure_hanzi_fields():
    existing = invoke("modelFieldNames", modelName=HANZI_MODEL_NAME)
    for field in NEW_HANZI_FIELDS:
        if field in existing:
            print(f"  [skip] {HANZI_MODEL_NAME!r} already has field {field!r}")
            continue
        invoke("modelFieldAdd", modelName=HANZI_MODEL_NAME, fieldName=field)
        print(f"  [add]  {field!r} -> {HANZI_MODEL_NAME!r}")


def ensure_hanzi_template():
    templates = invoke("modelTemplates", modelName=HANZI_MODEL_NAME)
    card_name, sides = next(iter(templates.items()))
    if CONSTITUENT_TREE_MARKER in sides["Back"]:
        print(f"  [skip] {HANZI_MODEL_NAME!r} {card_name!r} already has the tree renderer")
    else:
        new_back = sides["Back"] + "\n" + CONSTITUENT_TREE_HTML
        invoke(
            "updateModelTemplates",
            model={"name": HANZI_MODEL_NAME, "templates": {card_name: {"Front": sides["Front"], "Back": new_back}}},
        )
        print(f"  [add]  tree renderer -> {HANZI_MODEL_NAME!r} {card_name!r} Back template")

    css = invoke("modelStyling", modelName=HANZI_MODEL_NAME)["css"]
    if CONSTITUENT_TREE_MARKER in css:
        print(f"  [skip] {HANZI_MODEL_NAME!r} CSS already has the tree styles")
    else:
        invoke("updateModelStyling", model={"name": HANZI_MODEL_NAME, "css": css + "\n" + CONSTITUENT_TREE_CSS})
        print(f"  [add]  tree styles -> {HANZI_MODEL_NAME!r} CSS")


def ensure_word_model():
    if WORD_MODEL_NAME in invoke("modelNames"):
        print(f"  [skip] model {WORD_MODEL_NAME!r} already exists")
        return
    invoke(
        "createModel",
        modelName=WORD_MODEL_NAME,
        inOrderFields=WORD_FIELDS,
        css=WORD_CSS,
        cardTemplates=[{"Name": "Card 1", "Front": WORD_FRONT_TEMPLATE, "Back": WORD_BACK_TEMPLATE}],
    )
    print(f"  [add]  model {WORD_MODEL_NAME!r} with fields {WORD_FIELDS}")


def ensure_word_answer_tags():
    templates = invoke("modelTemplates", modelName=WORD_MODEL_NAME)
    card_name, sides = next(iter(templates.items()))
    new_sides = dict(sides)
    template_changed = False

    # Revision 1 put a small {{Tags}} badge on both Front and the Back's echoed front block --
    # strip that back out to the plain pre-tags shape wherever it's still present.
    for side_name, html in sides.items():
        if _FRONT_WITH_SMALL_TAGS in html:
            new_sides[side_name] = html.replace(_FRONT_WITH_SMALL_TAGS, WORD_FRONT_TEMPLATE.strip())
            template_changed = True

    back = new_sides["Back"]
    if 'id="tags-display"' in back:
        pass  # already migrated, nothing to do here
    elif _OLD_ANSWER_BLOCK in back:
        new_sides["Back"] = back.replace(_OLD_ANSWER_BLOCK, _NEW_ANSWER_BLOCK)
        template_changed = True
    else:
        print(f"  [warn] {WORD_MODEL_NAME!r} {card_name!r} Back doesn't match any known answer-"
              f"block shape -- leaving it alone, check by hand")

    if template_changed:
        invoke("updateModelTemplates", model={"name": WORD_MODEL_NAME, "templates": {card_name: new_sides}})
        print(f"  [add]  reformatted answer-side tags -> {WORD_MODEL_NAME!r} {card_name!r} template")
    else:
        print(f"  [skip] {WORD_MODEL_NAME!r} template already shows reformatted tags on the answer")

    css = invoke("modelStyling", modelName=WORD_MODEL_NAME)["css"]
    new_css = css
    for old_rule in _OLD_TAGS_CSS_RULES:
        new_css = new_css.replace(old_rule, "")
    if _TAGS_DISPLAY_CSS_MARKER not in new_css:
        new_css = new_css.replace(
            ".word { font-size: 48px; margin-bottom: 16px; }",
            ".word { font-size: 48px; margin-bottom: 16px; }\n"
            ".tags-display { font-size: 24px; font-weight: bold; color: #555; margin-bottom: 12px; }",
        )
        new_css = new_css.replace(
            ".nightMode .pinyin, .night_mode .pinyin { color: #7fb3ff; }",
            ".nightMode .tags-display, .night_mode .tags-display { color: #ccc; }\n"
            ".nightMode .pinyin, .night_mode .pinyin { color: #7fb3ff; }",
        )

    if new_css != css:
        invoke("updateModelStyling", model={"name": WORD_MODEL_NAME, "css": new_css})
        print(f"  [add]  .tags-display styling -> {WORD_MODEL_NAME!r} CSS")
    else:
        print(f"  [skip] {WORD_MODEL_NAME!r} CSS already correct")


def ensure_word_deck():
    if WORD_DECK_NAME in invoke("deckNames"):
        print(f"  [skip] deck {WORD_DECK_NAME!r} already exists")
        return
    invoke("createDeck", deck=WORD_DECK_NAME)
    print(f"  [add]  deck {WORD_DECK_NAME!r}")


def main():
    ensure_connected()
    print("Hanzi note type fields:")
    ensure_hanzi_fields()
    print("Hanzi note type template/styling:")
    ensure_hanzi_template()
    print("Chinese Word note type:")
    ensure_word_model()
    print("Chinese Word template tags rendering:")
    ensure_word_answer_tags()
    print("Chinese Word deck:")
    ensure_word_deck()
    print("Done. Sync AnkiDroid with this collection before using the new fields on the phone.")


if __name__ == "__main__":
    main()
