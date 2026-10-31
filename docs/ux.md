# Fulla's UX rules

How every screen behaves. `docs/design.md` says what Fulla looks like (the
mark, colour, type, motion); this file says how a screen works under a
finger and a keyboard. The mockups these rules came from are the
"Fulla UX redesign" canvas: one board per screen, plus a board with these
pieces drawn.

**Consistency comes first.** A form is a form everywhere, a list is a list
everywhere. A screen is built only from the pieces below, each made once in
`ui/components/` (`Pieces.kt`, `Forms.kt`) and reused. A screen that needs something no piece
covers adds a piece here and in `Pieces.kt` first; it never styles its own
one-off. If two screens differ where they could be the same, one of them is
wrong.

These rules came from watching people use the app: they could not find the
transaction details behind a small chevron, typed a note under the keyboard,
did not see the fields a choice had opened below the keyboard, and read
"Add a fixed cost" as one more row of the list.

## Measures

One set of numbers for the whole app, the ones `Pieces.kt` and `Type.kt`
already use. Never a size in between; a new size is a new token in those
files, never a number in a screen. Every height below is a minimum: the
piece grows with its text.

| What | Size |
|---|---|
| Side margin of a screen (`LocalRowInset`) | 20 dp; 16 dp inside a card |
| Smallest target | 48 dp |
| Text field, select | at least 56 dp tall (Material outlined field), full width of its column |
| Primary button (`PrimaryButton`) | at least 56 dp tall, full width, radius 16 dp |
| Secondary button (`SecondaryButton`) | at least 48 dp tall, full width, radius 16 dp |
| Chip (`Chip`, `DetailChip`) | at least 48 dp of target, radius of the Material chip |
| Segmented choice (`SegmentedChoice`) | 4 dp inset, segments at least 48 dp, radius 14 dp / 10 dp |
| Card (`CardGroup`) | radius 20 dp, 16 dp from the screen edge |
| List row (`ListRow`) | at least 56 dp, 14 dp between its parts |
| Space between fields | 12 dp; between a field and its help 4 dp |
| Section title (`Section`) | `FullaType.section`, upper case, `inkMuted` |
| Screen title | `FullaType.screenTitle` on a tab, `FullaType.title` on a screen with back |

## Alignment

- Every field, select, card and primary button in a column shares the same
  left and right edge: the screen's 20 dp margins.
- Fields side by side share the row: one takes the rest of the width
  (`weight(1f)`), the other a fixed width. Neither ever pushes the other past
  the edge. Fields in one row have the same height.
- Amounts are right-aligned with tabular figures, in rows and in fields.
- Labels sit above their field, never beside it; help sits right under it.
- A screenshot test fails when an element leaves its parent or the screen.
  "It looks fine on my phone" is not a check: a mockup once had an amount
  field 9 px past the edge of its row.

## Every phone, every text size

People use Fulla on small and large screens and with the system font turned
up. A screen adapts to them; it never looks broken and never loses an action.

- **Sizes that hold at any width from 320 dp to a tablet.** Nothing has a fixed
  width except small things (a day field, an icon tile). Columns fill the
  width; a row that cannot fit wraps its chips or stacks its fields.
- **Text in `sp`, never fixed heights around text.** A row, chip, button or
  card is at least its measure above and grows with its text: `heightIn(min =)`,
  never `height()`. Labels wrap to two lines rather than cut; only a name in a
  list row may end in "…", and then its full text is in the content
  description.
- **Up to 200 % font size** works: segmented choices that no longer fit
  become a stacked list of options; two fields side by side stack one above
  the other; amounts never wrap mid-number (they shrink a step instead, down
  to the label size, and never below it).
- **The primary button and the keyboard rules hold at every size.** On a
  short screen or with big text the content scrolls under a pinned primary
  button; the button itself never scrolls away and is never covered.
- **Landscape and split screen** keep the same order; nothing depends on the
  screen being tall.
- **Tested, not hoped.** Every screen's screenshot test runs at 320 dp wide,
  at a normal phone width, and at 200 % font scale, in light and dark, and
  fails on anything clipped, overlapping or outside its parent.

## The pieces

### 1. Primary button

One per screen, full width, at the bottom, always visible (pinned above the
navigation bar and above the keyboard). Its label is the action and its
figure: "Save expense · €12.50", "Import 42 lines". It is never just grey: when
something is missing, the button or a line under it says what.

### 2. Form

A full screen, never a dialog with a scrolling body. Title and close at the
top; fields in the order a person thinks of them; the primary button pinned
at the bottom. An error sits under its own field, not in a toast.

### 3. With the keyboard open

The field being typed in moves into view (`imePadding` on the screen,
`bringIntoViewRequester` on the field); the primary button rides on top of
the keyboard. Nothing a person needs is ever under the keyboard. A bottom
sheet with a text field is `imePadding` too, or the text goes to a bar pinned
above the keyboard (the note on Add).

### 4. Content that appears

What a choice opens (an end date, a number of payments, months to pick)
appears right under the choice that opened it, never at the end of the form.
It is highlighted for a moment and the screen scrolls to it; if it needs
typing, the cursor is already in it.

### 5. Picking sheet

A short bottom sheet: one tap picks and closes it. No "Done", no scroll. More
than six options get a search field at the top. The screen behind still shows
above it, so the person knows where they are.

### 6. Detail chips

A detail that has a value (date, account, payer, split, trip, a custom field)
is a chip that shows its value and a ▾; tapping it opens a picking sheet (5).
A detail with no value yet is a dashed chip with a +: "+ Note", "+ Fixed".
Details are never hidden behind a chevron or a summary line.

### 7. On and off

Anything that is switched on, off or paused is a visible switch. Never "tap to
pause it" in grey text. What is paused is shown dimmed.

### 8. Confirming something serious

Only for deleting, leaving or losing data. The title names the thing ("Delete
this expense?"), one line says what happens next ("It goes from Bob's phone
too"), and the button is red with the exact verb ("Delete expense"), never
"OK". Cancel beside it.

### 9. Empty screen

Says what will be here and what it is for, and carries the button that
creates the first one. Never a blank screen.

### 10. Notice

A card at the top of the screen in the warning colour: one sentence, one
direct action ("Have you been paid yet? Yes"). It can be dismissed and does
not come back until it applies again.

### 11. Lists

- Every row: the icon of its category (or a person's colour and initial),
  the name, the one fact that matters (next date, "1 of 3 paid" as a bar) and
  the amount on the right.
- Ordered by what is useful (next charge, date), not alphabetically; grouped,
  with a total for each group.
- Long lists get a search field at the top.
- "Add" is never a row of the list: it is the primary button (1).
- If there is more to the side (filter chips), a piece of it shows, with a
  fade.

### 12. Steps and words

- A long process shows a step bar and "Step 2 of 4", and "Skip" when a step
  can be skipped.
- Explanations are one short line under the option, never a paragraph above
  it. An example beats a definition ("October runs from 25 Sep to 24 Oct").

## Where the app breaks these today

What to change first, found in the code on 2026-10-31:

- Bottom sheets (`EntryScreen`'s details sheet, the sheets in `Pieces.kt`)
  have no `imePadding`: the keyboard covers the note and "Done" (rule 3).
- Add's details sit behind a summary line with a chevron (rule 6).
- The fixed-cost form is a `FullaDialog` with a scrolling body
  (`RecurringSettings.kt`): what "After some payments" opens appears below
  the keyboard (rules 2, 3, 4).
- "Add a fixed cost" is a `ListRow` like the costs above it
  (`RecurringSettings.kt`, rule 11); its rows are three lines of grey text in
  alphabetical order.
- Several screens say "Tap to …" in grey instead of a button or a switch
  (Balances' settle up, Insights' "make it recurring", Rules' pause; rules 1
  and 7).

## Before saying a screen is done

- It is built only from the pieces above, with the measures above.
- Open it with the keyboard up on every field: nothing hides.
- Tap every choice that opens something: it appears under the choice.
- Run the screenshot test: nothing leaves its parent or the screen.
