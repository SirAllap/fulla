# Fulla's design system

Fulla is the Norse goddess of abundance; her name means *full*. She keeps
Frigg's casket and wears a golden band. The app takes two things from her: a
vessel that fills, and the gold.

## The mark

A golden band, filled a little more than half, the surface at rest. It is the
launcher icon (`res/drawable/ic_launcher_foreground.xml`, on ink), the welcome
screen (`ui/components/BrandMark.kt`, animated) and nothing else. The mark is
always gold, whatever accent a phone has chosen: the accent is the phone's,
the mark is Fulla's.

Images built from the mark live in `docs/brand/`: the README hero (light and
dark) and the social preview (1280×640) for the repository's settings. They
use the same mark, colours and type as the app.

## Colour

Every colour lives in `core/.../design/Colors.kt`, where `ColorsTest` holds
every colour that carries text to 4.5:1. Screens ask `FullaTheme.colors` for a
role, never for a hex value.

| Role | Light | Dark | Used for |
|---|---|---|---|
| `paper` / `paperHigh` | warm off-white | deep ink | grounds |
| `ink` / `inkMuted` | ink | cream | text, icons |
| `accent` / `onAccent` | ink | the accent | the primary action |
| `highlight` / `onHighlight` | the accent, ink on it | the same | what is selected: tab, chip, tile |
| `danger` | | | destructive actions; no setting changes it |
| money in / out | gold / ink by default | | amounts and the two liquids |

**Accent**, per phone in Settings › Appearance: gold (Fulla's, the default),
lavender, sea, sage, rose, ember, platinum. Every accent is light enough to
carry ink, and reads as text on the dark paper; the test checks each one.

**Money colours** are a separate choice (Ink and gold, Amber, Sea, Forest,
Classic), so what is selected and what is money never share a meaning by
accident.

Never colour alone: a person is a colour *and* initials; income carries its
sign.

## Type

Archivo throughout; tabular figures wherever numbers sit in a column,
right-aligned.

## Motion

`ui/theme/Motion.kt` has the tokens, `ui/components/Motion.kt` the pieces.

- **Springs, not curves**, for anything that answers a touch. `snappy` for
  what follows a finger or a choice, `settle` for shapes that change size or
  place (a hint of overshoot, like a liquid), `trail` for the lazy edge of
  something that stretches. Never a bounce.
- **Things yield.** Buttons, keys, chips and tabs shrink 3 % under the finger
  (`Modifier.yields`).
- **The tab pill is a drop.** Its two edges ride different springs, so it
  stretches towards the new tab and gathers there (`LiquidTabBar`).
- **Saving gathers into a ✓.** The keypad's save key shrinks to a circle of
  the accent, the check pops, then the screen moves on.
- **Screens slide a little.** What opens comes in from the side while what
  was there steps back and dims; back is the same, reversed.
- **The welcome plays once.** The band draws itself, the liquid rises and
  settles with a slosh, the words and actions arrive out of a blur (~1.1 s).
- **Still on purpose:** lists have no entrance animation, nothing moves on
  its own after the welcome, no skeletons or spinners in the main flows.
- **Remove animations** in the system settings and everything snaps.

## Components

`ui/components/Pieces.kt`: headers, rows, sections, chips, buttons, empty
states. If two screens need the same thing, it is a component.

- 48 dp minimum targets; primary actions in the bottom third.
- One primary action per screen.
