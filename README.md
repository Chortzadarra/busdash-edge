# busdash-edge

Edge-applicatie voor de Raspberry Pi in de bus. Stap 1: Victron BMV-712 uitlezen via VE.Direct.

## Starten in IntelliJ
1. *File → Open* → kies deze map (de `pom.xml` wordt automatisch herkend).
2. Draai de tests: rechtsklik op `src/test/java` → *Run 'All Tests'*.
3. Draai `App` met programma-argument `--fake` voor gesimuleerde accudata.

## Met echte hardware
- `--list-ports` toont de beschikbare seriële poorten.
- `--port /dev/ttyUSB0` (Pi/Linux) of `--port COM3` (Windows) leest de BMV uit.

## Naar de Pi
```bash
mvn package
scp target/busdash-edge-0.1.0-SNAPSHOT.jar pi@<ip>:~/
ssh pi@<ip> java -jar busdash-edge-0.1.0-SNAPSHOT.jar --port /dev/ttyUSB0
```
Op de Pi moet de gebruiker in de groep `dialout` zitten om de seriële poort te mogen openen:
`sudo usermod -aG dialout $USER` (daarna opnieuw inloggen).

Gebruik op de Pi bij voorkeur de vaste naam uit `/dev/serial/by-id/` in plaats van `/dev/ttyUSB0`:
dat nummer kan veranderen als de kabel opnieuw wordt ingestoken.

### Automatisch starten
In `deploy/busdash-edge.service` staat een systemd-service; installatie-instructies staan bovenin dat bestand.

### Gedrag bij storingen
- Is de poort bij het starten (nog) niet beschikbaar, of valt de kabel weg, dan blijft de app
  opnieuw verbinden (wachttijd oplopend van 2 tot 30 seconden).
- Is de laatste geldige meting ouder dan 10 seconden, dan geeft de monitor geen meting meer terug
  in plaats van een verouderde waarde.

## Relaisbord (Waveshare RPi Relay Board (B), 8 kanalen)
Aansturing via GPIO met Pi4J en libgpiod (werkt ook op de Pi 5). De gebruiker moet in de groep `gpio`
zitten: `sudo usermod -aG gpio $USER`.

- `--relay-test` zet CH1 t/m CH8 één voor één een seconde aan. **Doe dit eerst zonder iets aangesloten**
  en controleer dat het juiste relais klikt (LED op het bord), want de pinindeling
  (BCM 5, 6, 13, 16, 19, 20, 21, 26) komt uit de Waveshare-documentatie.
- `--relay-console` om met de hand te schakelen: `3 aan`, `3 uit`, `alles uit`, `status`, `stop`.
- Voeg `--fake` toe om zonder Pi te testen, bijv. `--relay-console --fake`.

### Dashboard
`--dashboard` start een webpagina om de relais te schakelen, geschikt voor telefoon en computer.
Open `http://<ip-van-de-pi>:8080/` op een apparaat in hetzelfde netwerk (`--fake` werkt ook op je laptop:
`http://localhost:8080/`).

- Elk relais heeft een vast nummer 1 t/m 8 (= CH1..CH8 op het bord) en een eigen naam (potloodje).
- Standaard is alleen relais 1 zichtbaar; met **+** voeg je de volgende toe, tot maximaal 8.
  Via het potloodje kun je een relais ook weer verwijderen (het gaat dan uit).
- Namen en zichtbaarheid worden bewaard in `~/.busdash/relays.properties` (aan te passen met `--config`).
  Of een relais aan of uit staat wordt niet bewaard: na een herstart staat alles uit.
- Andere poort: `--http-port 8081`. De pagina ververst elke 3 seconden, dus meerdere telefoons blijven gelijk.
- Er is geen login: iedereen op het netwerk kan schakelen. Prima op de wifi in de bus, maar zet de poort
  niet open naar internet.

Het bord is low-active (pin laag = relais aan); dat zit verstopt in `WaveshareRelayBoard`.
Bij afsluiten of Ctrl+C gaan alle relais uit.

Klikken er relais aan tijdens het opstarten van de Pi, voordat de app draait? Zet de pinnen dan
al vroeg hoog door deze regel toe te voegen aan `/boot/firmware/config.txt`:
```
gpio=5,6,13,16,19,20,21,26=op,dh
```

Let op: de contacten kunnen maximaal 30 V DC / 5 A (ohmse last) schakelen. Laat zwaardere of
inductieve 12V-verbruikers (koelkast, omvormer, pomp) via een zwaarder relais of contactor lopen.

## Structuur
- `vedirect/` – protocol: streaming parser met checksum-validatie, seriële bron
- `battery/` – domein: `BatteryMonitor`-interface met een echte (BMV) en een fake implementatie
- `relay/` – `RelayBoard`-interface met het echte Waveshare-bord en een fake, plus een console om te testen
- `web/` – dashboard: kleine webserver (zit in de JDK) met een JSON-API; de pagina staat in
  `src/main/resources/dashboard/index.html`

## Volgende stappen
MQTT-publish → Spring Boot-backend → dashboard → schakelen.
