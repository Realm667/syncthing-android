# Syncthing ES-DE Safe Sync v2.1.4.14

## Optimierungen

- First Setup aktualisiert Geräte- und Ordnerauswahl anhand der tatsächlichen
  Service-Bereitschaft und Konfigurationsänderungen, ohne 40-Sekunden-Refresh-Limit.
- Offline-Journalzugriffe laufen serialisiert im Hintergrund. Die Oberfläche
  verwendet einen beobachtbaren Snapshot; ein fehlgeschlagener Zugriff ist erneut
  versuchbar. „Safe to switch“ erscheint erst nach erfolgreichem Journalabschluss.
- Automatische ES-DE-Einstellungsänderungen verwenden dieselbe Warteschlange wie
  Metadatenimporte und prüfen zuvor, dass ES-DE geschlossen ist.
- Metadatenimporte parsen jede gamelist.xml nur einmal. Unveränderte Snapshots
  werden nicht neu geschrieben; Backups entstehen nur vor tatsächlichen Änderungen.
- Diagnosen aktualisieren betroffene Systeme gezielt; Sidecar-Ereignisse werden
  pro System gebündelt, einschließlich eines Nachlaufs für Änderungen beim Import.
- Statusabfragen reduzieren ihre Häufigkeit bei unverändertem Zustand. Verspätete
  Antworten nach Retry, Spielstart oder Sitzungsende werden verworfen.
- Späte Bereitschaftsmeldungen verlassen weder Idle noch beenden sie eine gerade
  gestartete Spielsitzung.

## Installation und Prüfung

- Update über die interne Updatefunktion oder die universelle Release-APK.
- Application ID und Signierschlüssel bleiben unverändert; vorhandene
  Installationen können ohne Deinstallation aktualisiert werden.
- Keine neuen Ignore-Regeln, Ordnerzuweisungen oder Datenmigrationen erforderlich.
- Lokale Prüfung vor dem Release: 87 Unit-Tests bestanden, Kotlin/Java kompiliert,
  Android Lint ohne Fehler (45 Warnungen, 1 Hinweis). Der Release-Workflow führt
  reguläre Tests, Lint und den vollständigen APK-Bau erneut aus.
- Keine Tests auf physischen Handhelds in diesem Durchlauf.
- Die APK verwendet weiterhin den stabilen, öffentlichen Testschlüssel für
  persönliche Installationen. Sie ist kein vertrauenswürdiger Store-/Produktionsbuild.

Prüfsummen werden als `SHA256SUMS.txt` veröffentlicht.
