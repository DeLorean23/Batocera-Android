# DeLorean Station

Emulator-Frontend für Android im Stil von Batocera. Created by DeLorean23

## Was die App kann
- Oberfläche im Carbon-Look: Systemkarussell und Spieleliste mit Cover und Infos aus der `gamelist.xml`
- Ordnerstruktur wie Batocera (`/storage/emulated/0/batocera`, im Menü änderbar):
  - `roms/<system>/` mit `gamelist.xml`, `images/`, `videos/`
  - `bios/` – BIOS-Dateien (selbst einfügen)
  - `saves/<system>/` – Spielstände (`.srm`) und Savestates (`.state`, `.state1` …)
  - `themes/` – Theme-Ordner, z. B. `themes/es-theme-carbon`
- Speichermedium **Intern** oder **Netzwerk (SMB)**: Im Netzwerk-Modus werden Systeme, gamelist und Cover direkt von der Freigabe gelesen. Beim Start wird das Spiel samt Cover und gamelist-Eintrag in den passenden lokalen Ordner `roms/<system>` kopiert.
- Eingebaute Emulatoren (Libretro-Cores), Liste in `cores.txt`; welche wirklich in der APK stecken, steht in `Cores.txt` in der ZIP
- Voller Xbox-One-Controller auf dem Touchscreen (beide Sticks, Steuerkreuz, A/B/X/Y als Raute, LB/RB, LT/RT, L3/R3, Ansicht, Menü, Xbox-Taste); im Spiel und in der Oberfläche, blendet sich bei angeschlossenem Controller automatisch aus

## Theme
Die Theme-Dateien werden **nicht** mit der App ausgeliefert. Den Ordner `es-theme-carbon` von deinem Batocera-PC (`/userdata/themes` oder aus dem System-Theme) nach `batocera/themes/` kopieren.

## Hotkeys (wie Batocera, Hotkey = Ansicht-Taste)
| Kombination | Funktion |
|---|---|
| Ansicht + Menü | Spiel beenden |
| Ansicht + A (unten) | Spielmenü |
| Ansicht + B (rechts) | Neustart |
| Ansicht + X (links) | Savestate speichern |
| Ansicht + Y (oben) | Savestate laden |
| Ansicht + Hoch / Runter | Slot wechseln |
| Ansicht + Rechts | Schnellvorlauf |
| Xbox-Taste / Zurück | Spielmenü |

## BIOS-Hinweise
- PSP: Ordner `bios/PPSSPP` (Assets von PPSSPP)
- GameCube/Wii: Ordner `bios/dolphin-emu/Sys`
- PlayStation, Saturn, Mega CD, PC Engine CD usw.: BIOS-Dateien wie bei Batocera direkt in `bios/`

## Bauen
GitHub Actions baut bei jedem Push beide APKs (32-bit `armeabi-v7a`, 64-bit `arm64-v8a`) und legt sie als `DeLoreanStation.zip` unter Releases ab. GameCube/Wii (Dolphin) und 3DS gibt es nur in der 64-bit-APK.

## Lizenz
Nutzt LibretroDroid (GPL-3) und Libretro-Cores (jeweils eigene Lizenzen), daher steht die App unter GPL-3.
