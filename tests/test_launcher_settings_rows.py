"""docs/launcher.md: the Launcher settings section names every row the settings screen has.

The section moved out of the README into docs/launcher.md, which the README's launcher section links.

The rows are read from the screen, not from a list kept here. `activity_launcher_settings.xml` carries
each row as a `SettingsItem` with a `SettingsItemTitle` label, so a row added to the screen without a
docs line fails in CI, while rewording a docs line stays free as long as the row's own label - the text
the TV shows - is still there. The values a row steps through (4 to 7 apps per row, 0 to 3 rows below the
dock, the four dock opacities) come from the arrays in `LauncherSettingsActivity`.
"""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAYOUT = ROOT / "launcher/app/src/main/res/layout/activity_launcher_settings.xml"
ACTIVITY = ROOT / "launcher/app/src/main/java/com/gathin/tvhome/LauncherSettingsActivity.java"
STRINGS = ROOT / "launcher/app/src/main/res/values/strings.xml"
GUIDE = ROOT / "docs" / "launcher.md"

ANDROID = "{http://schemas.android.com/apk/res/android}"
SUMMARY = "Launcher settings and the default home"


def attr(view, name):
    return view.get(ANDROID + name) or ""


def english_strings(text):
    """name -> value for every <string> in res/values/strings.xml (the English text Android shows)."""
    out = {}
    for match in re.finditer(r"<string name=\"([a-z_0-9]+)\"[^>]*>(.*?)</string>", text, re.S):
        out[match.group(1)] = match.group(2).strip()
    return out


def normalise(text):
    """One line, so a label the README wraps across two lines still matches."""
    return re.sub(r"\s+", " ", text.replace("**", "").replace("\\", "")).strip()


def row_label(name, strings):
    """A resource name as the row's label: 'animations_state' ("Animations: %1$s") is 'Animations'."""
    value = strings.get(name)
    if value is None:
        return None
    value = re.sub(r"%\d\$s", "", value)
    return value.strip().rstrip(":").strip() or None


def row_labels(layout_text, activity_text, strings):
    """Every row label the settings screen shows, in screen order, as [(id, label)].

    A row is a `SettingsItem`; its label is the `SettingsItemTitle` text inside it, a string resource.
    Section captions (`SettingsSection`) and the preview pane are not rows.
    """
    rows = []
    for view in ET.fromstring(layout_text).iter():
        if not (view.get("style") or "").endswith("SettingsItem"):
            continue
        for child in view:
            text = attr(child, "text")
            if (child.get("style") or "").endswith("SettingsItemTitle") and text.startswith("@string/"):
                label = row_label(text[len("@string/"):], strings)
                if label:
                    rows.append((attr(view, "id").split("/")[-1] or text, label))
                break
    return rows


def missing_rows(section, rows):
    """The (id, label) rows the section does not name."""
    seen = normalise(section).lower()
    return [(row_id, label) for row_id, label in rows if label.lower() not in seen]


def numbered(array, activity_text):
    """The values one of the activity's int arrays (COLUMN_CHOICES, ROW_CHOICES, DOCK_OPACITIES) steps
    through, as strings in order; [] when the array is not there."""
    found = re.search(r"\b%s\s*=\s*\{([0-9,\s]+)\}" % re.escape(array), activity_text)
    if not found:
        return []
    return [value.strip() for value in found.group(1).split(",") if value.strip()]


def detail_section(text, summary):
    """The text under one `## summary` heading up to the next `## `, or '' - so a row named three
    sections further down does not count as the settings page being documented."""
    start = text.find("## %s\n" % summary)
    if start < 0:
        return ""
    end = text.find("\n## ", start + 3)
    return text[start:end if end > 0 else len(text)]


class ScreenRows(unittest.TestCase):
    """The README against the settings screen it describes."""

    def setUp(self):
        self.layout_text = LAYOUT.read_text(encoding="utf-8")
        self.activity_text = ACTIVITY.read_text(encoding="utf-8")
        self.strings = english_strings(STRINGS.read_text(encoding="utf-8"))
        self.rows = row_labels(self.layout_text, self.activity_text, self.strings)
        self.section = detail_section(GUIDE.read_text(encoding="utf-8"), SUMMARY)

    def test_screen_rows_are_read(self):
        """The screen has its rows, and each one resolved to the label the TV shows."""
        self.assertGreaterEqual(len(self.rows), 9, self.rows)
        labels = [label for _, label in self.rows]
        self.assertEqual(len(labels), len(set(labels)), labels)
        for row_id, label in self.rows:
            self.assertTrue(label.strip(), row_id)

    def test_readme_names_every_row(self):
        self.assertTrue(self.section, "docs/launcher.md has no '%s' section" % SUMMARY)
        self.assertEqual(missing_rows(self.section, self.rows), [])

    def test_readme_names_the_numbers_under_each_row(self):
        columns = numbered("COLUMN_CHOICES", self.activity_text)
        under_dock = numbered("ROW_CHOICES", self.activity_text)
        opacities = numbered("DOCK_OPACITIES", self.activity_text)
        self.assertEqual(columns, ["4", "5", "6", "7"])
        self.assertEqual(under_dock, ["0", "1", "2", "3"])
        self.assertEqual(len(opacities), 4, opacities)
        section = normalise(self.section)
        self.assertIn("%s, %s, %s or %s" % tuple(columns), section)
        self.assertIn("%s to %s" % (under_dock[0], under_dock[-1]), section)
        self.assertIn("%s%%, %s%%, %s%% or %s%%" % tuple(opacities), section)

    def test_a_row_the_readme_leaves_out_is_reported(self):
        rows = [("reorderChoice", "Reorder apps"), ("walls", "Wallpaper")]
        self.assertEqual(missing_rows("- **Reorder apps** only with the button.", rows),
                         [("walls", "Wallpaper")])

    def test_a_row_named_in_another_section_does_not_count(self):
        readme = ("## %s\n\nNothing here.\n\n"
                  "## Elsewhere\n\n- **Wallpaper** this is not the settings page.\n" % SUMMARY)
        section = detail_section(readme, SUMMARY)
        self.assertIn("Nothing here", section)
        self.assertNotIn("Elsewhere", section)
        self.assertEqual(missing_rows(section, [("wallpaperChoice", "Wallpaper")]),
                         [("wallpaperChoice", "Wallpaper")])

    def test_a_placeholder_is_not_part_of_the_label(self):
        strings = {"animations_state": "Animations: %1$s", "wallpaper_label": "Wallpaper"}
        self.assertEqual(row_label("animations_state", strings), "Animations")
        self.assertEqual(missing_rows("**Animations** on or off", [("a", "Animations")]), [])
        self.assertEqual(missing_rows("**Animations: %s** on or off", [("a", "Animations")]), [])

    def test_a_label_wrapped_across_lines_still_matches(self):
        section = "**Follow theme (reset on-TV\n  choices)** clears the choices."
        self.assertEqual(missing_rows(section, [("f", "Follow theme (reset on-TV choices)")]), [])
        self.assertEqual(missing_rows(normalise(section),
                                       [("f", "Follow theme (reset on-TV choices)")]), [])

    def test_a_screen_with_no_rows_reads_as_no_rows(self):
        layout = ('<ScrollView xmlns:android="http://schemas.android.com/apk/res/android">'
                  '<TextView android:text="Hi" android:textSize="34sp" /></ScrollView>')
        self.assertEqual(row_labels(layout, "", {}), [])
        self.assertEqual(numbered("COLUMN_CHOICES", ""), [])

    def test_a_row_is_read_from_its_title(self):
        layout = ('<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">'
                  '<TextView style="@style/SettingsSection" android:text="@string/look" />'
                  '<LinearLayout android:id="@+id/rowTheme" style="@style/SettingsItem">'
                  '<TextView style="@style/SettingsItemTitle" android:text="@string/theme_label" />'
                  '<TextView android:id="@+id/valueTheme" style="@style/SettingsItemValue" />'
                  '</LinearLayout></LinearLayout>')
        strings = {"theme_label": "Theme", "look": "Look"}
        self.assertEqual(row_labels(layout, "", strings), [("rowTheme", "Theme")])


if __name__ == "__main__":
    unittest.main()
