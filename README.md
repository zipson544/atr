# AutoRynek (Fabric 1.21.4, client-side)

Budowa: wrzuc na GitHub -> zakladka Actions -> workflow `build` -> artefakt `autorynek-jar`.
Wymaga Fabric Loader + Fabric API. Plik jar wrzuc do `mods/`.

- `/autorynekgui` - GUI (Status / Zakupy / Config / Skan / Ustawienia)
- Ctrl+A w rynku/ekwipunku - dodaj przedmiot pod kursorem do configu
- Klawisze (Sterowanie -> AutoRynek): START/STOP (domyslnie `]`), PANIC (domyslnie `END`)
- Config: `.minecraft/config/autorynek.json` (webhook jest tam, NIE w repo)
